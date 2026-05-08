package hotwire

import munit.FunSuite
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.server.Route

import scala.concurrent.Await
import scala.concurrent.duration.*

/** Smoke tests for the infinite-scroll feed. Asserts the turbo-frame contract:
  *
  *   - `GET /posts` returns the first batch plus a lazy placeholder for page 2.
  *   - `GET /posts/page/N` returns a `<turbo-frame id="posts-page-N">` whose body
  *     contains the page's items and (unless this is the last page) a nested lazy
  *     placeholder for the next page.
  *   - The last page returns no further placeholder, so the chain terminates.
  */
class PostsRoutesSpec extends FunSuite:

  given system: ActorSystem[Nothing] =
    ActorSystem(Behaviors.empty, "PostsRoutesSpec")

  override def afterAll(): Unit = system.terminate()

  // 25 items, 10 per page → 3 pages (10, 10, 5).
  private def newHandler(): HttpRequest => scala.concurrent.Future[HttpResponse] =
    Route.toFunction(new PostsRoutes(perPage = 10, total = 25).routes)

  private def body(resp: HttpResponse): String =
    given ec: scala.concurrent.ExecutionContext = system.executionContext
    Await.result(resp.entity.toStrict(5.seconds).map(_.data.utf8String), 5.seconds)

  test("GET /posts returns first 10 items plus a lazy frame for page 2") {
    val resp = Await.result(newHandler()(HttpRequest(uri = "/posts")), 5.seconds)
    assertEquals(resp.status, StatusCodes.OK)
    val html = body(resp)
    assert(html.contains("""id="post-1""""), "expected post 1 on page 1")
    assert(html.contains("""id="post-10""""), "expected post 10 on page 1")
    assert(!html.contains("""id="post-11""""), "page 1 should not contain post 11")
    assert(
      html.contains("""<turbo-frame id="posts-page-2" loading="lazy" src="/posts/page/2">"""),
      html
    )
  }

  test("GET /posts/page/2 returns a turbo-frame with that id and a placeholder for page 3") {
    val resp = Await.result(newHandler()(HttpRequest(uri = "/posts/page/2")), 5.seconds)
    assertEquals(resp.status, StatusCodes.OK)
    val html = body(resp)
    assert(html.contains("""<turbo-frame id="posts-page-2">"""), html)
    assert(html.contains("""id="post-11""""), "expected post 11 on page 2")
    assert(html.contains("""id="post-20""""), "expected post 20 on page 2")
    assert(
      html.contains("""<turbo-frame id="posts-page-3" loading="lazy" src="/posts/page/3">"""),
      html
    )
  }

  test("GET /posts/page/3 (last page) returns the remaining items and no further placeholder") {
    val resp = Await.result(newHandler()(HttpRequest(uri = "/posts/page/3")), 5.seconds)
    assertEquals(resp.status, StatusCodes.OK)
    val html = body(resp)
    assert(html.contains("""<turbo-frame id="posts-page-3">"""), html)
    assert(html.contains("""id="post-25""""), "expected post 25 on the last page")
    assert(!html.contains("""posts-page-4""""), "last page must not link to a further page")
    assert(html.contains("end of feed"), "expected end-of-feed marker on last page")
  }

  test("GET /posts/page/99 clamps to the last page rather than 404-ing") {
    val resp = Await.result(newHandler()(HttpRequest(uri = "/posts/page/99")), 5.seconds)
    assertEquals(resp.status, StatusCodes.OK)
    val html = body(resp)
    assert(html.contains("""<turbo-frame id="posts-page-3">"""), html)
  }
