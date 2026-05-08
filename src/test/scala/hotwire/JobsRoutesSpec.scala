package hotwire

import munit.FunSuite
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.{Cookie, Location, `Set-Cookie`}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.Sink

import scala.concurrent.Await
import scala.concurrent.duration.*

/** Smoke tests for JobsRoutes. Mirrors `ChatRoutesSpec` for HTTP, plus a fast worker
  * test using a 5ms tick so the whole suite runs in <100ms. */
class JobsRoutesSpec extends FunSuite:

  given system: ActorSystem[Nothing] =
    ActorSystem(Behaviors.empty, "JobsRoutesSpec")

  override def afterAll(): Unit = system.terminate()

  private def newRoutes(steps: Int = 5): (BroadcastBus, JobsRoutes) =
    val bus = new InProcessBroadcastBus()
    (bus, new JobsRoutes(bus, tickInterval = 5.millis, defaultSteps = steps))

  private def handler(r: JobsRoutes): HttpRequest => scala.concurrent.Future[HttpResponse] =
    Route.toFunction(r.routes)

  test("GET /jobs renders and sets a csrf cookie") {
    val (_, r) = newRoutes()
    val resp   = Await.result(handler(r)(HttpRequest(uri = "/jobs")), 5.seconds)
    assertEquals(resp.status, StatusCodes.OK)
    val cookie = resp.headers.collectFirst {
      case `Set-Cookie`(c) if c.name == "csrf_token" => c
    }
    assert(cookie.isDefined, s"expected Set-Cookie csrf_token, got ${resp.headers}")
  }

  test("POST /jobs without CSRF is rejected") {
    val (_, r) = newRoutes()
    val resp = Await.result(
      handler(r)(HttpRequest(HttpMethods.POST, "/jobs")),
      5.seconds
    )
    assertNotEquals(resp.status, StatusCodes.SeeOther)
  }

  test("POST /jobs with CSRF redirects to /jobs/:id") {
    val (_, r) = newRoutes()
    val token  = "test-token-123"
    val req    = HttpRequest(HttpMethods.POST, "/jobs")
      .addHeader(Cookie("csrf_token" -> token))
      .withEntity(FormData("_csrf" -> token).toEntity)
    val resp = Await.result(handler(r)(req), 5.seconds)
    assertEquals(resp.status, StatusCodes.SeeOther)
    val loc = resp.headers.collectFirst { case Location(uri) => uri.toString }
    assert(loc.exists(_.startsWith("/jobs/")), s"expected redirect to /jobs/<id>, got $loc")
  }

  test("worker publishes N progress frames + 1 done frame") {
    val (bus, r) = newRoutes(steps = 5)
    val id       = "test-id"
    // Subscribe BEFORE start so we capture every frame.
    val collected = bus.subscribe(s"job:$id")
      .take(6) // 5 progress + 1 done
      .runWith(Sink.seq)

    r.start(id, totalSteps = 5)

    val frames = Await.result(collected, 5.seconds)
    assertEquals(frames.size, 6)
    assert(frames.take(5).forall(_.contains("""target="job-test-id-progress"""")), frames.toString)
    assert(frames(5).contains("Job test-id done"), frames(5))
    assert(!frames.head.contains("Back to jobs"), frames.head)
  }
