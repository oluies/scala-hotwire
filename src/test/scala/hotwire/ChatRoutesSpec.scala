package hotwire

import munit.FunSuite
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.{Cookie, `Set-Cookie`}
import org.apache.pekko.http.scaladsl.server.Route

import scala.concurrent.Await
import scala.concurrent.duration.*

/** Smoke tests for the chat routes — exercises the CSRF directive and the Turbo Stream
  * content type on the form-response path. The WS broadcast path is exercised manually:
  * see README ("Try it out").
  */
class ChatRoutesSpec extends FunSuite:

  given system: ActorSystem[Nothing] =
    ActorSystem(Behaviors.empty, "ChatRoutesSpec")

  override def afterAll(): Unit = system.terminate()

  private def newHandler(): HttpRequest => scala.concurrent.Future[HttpResponse] =
    val bus = new InProcessBroadcastBus()
    Route.toFunction(new ChatRoutes(bus).routes)

  test("GET /chat/lobby sets a csrf_token cookie and returns 200") {
    val handler = newHandler()
    val resp    = Await.result(handler(HttpRequest(uri = "/chat/lobby")), 5.seconds)

    assertEquals(resp.status, StatusCodes.OK)
    val cookie = resp.headers.collectFirst {
      case `Set-Cookie`(c) if c.name == "csrf_token" => c
    }
    assert(cookie.isDefined, s"expected Set-Cookie csrf_token, got ${resp.headers}")
  }

  test("POST without CSRF is rejected") {
    val handler = newHandler()
    val form = FormData("author" -> "alice", "body" -> "hi").toEntity
    val resp = Await.result(
      handler(HttpRequest(HttpMethods.POST, "/chat/lobby/messages", entity = form)),
      5.seconds
    )
    assertNotEquals(resp.status, StatusCodes.OK)
  }

  test("POST with matching CSRF cookie + _csrf field returns turbo-stream content") {
    val handler = newHandler()
    val token   = "test-token-123"
    val form    = FormData("author" -> "alice", "body" -> "hi", "_csrf" -> token).toEntity
    val req = HttpRequest(HttpMethods.POST, "/chat/lobby/messages", entity = form)
      .addHeader(Cookie("csrf_token" -> token))

    val resp = Await.result(handler(req), 5.seconds)
    assertEquals(resp.status, StatusCodes.OK)
    assertEquals(resp.entity.contentType.mediaType.value, "text/vnd.turbo-stream.html")

    given ec: scala.concurrent.ExecutionContext = system.executionContext
    val body = Await.result(
      resp.entity.toStrict(5.seconds).map(_.data.utf8String),
      5.seconds
    )
    assert(body.contains("""action="append""""), body)
    assert(body.contains("""target="messages""""), body)
    assert(body.contains("<template>"), body)
  }
