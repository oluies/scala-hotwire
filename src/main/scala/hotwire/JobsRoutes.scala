package hotwire

import hotwire.TwirlSupport.given
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.Materializer.matFromSystem
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}

import java.time.Instant
import java.util.UUID
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

/** Background-job progress demo. The publisher is a `Source.tick` materialised on the
  * ActorSystem dispatcher — i.e. a non-HTTP publisher, the case the bus abstraction
  * was designed for. One Turbo Stream frame per tick is published on `topicFor(id)`
  * and fanned out to every WS subscribed to `/streams/jobs/<id>`.
  *
  * Per-job state is in-memory; if the node dies the job is lost. Durable jobs need
  * a job store and are out of scope.
  */
final class JobsRoutes(
    bus: BroadcastBus,
    tickInterval: FiniteDuration = 200.millis,
    defaultSteps: Int = 100
)(using system: ActorSystem[?]):
  import JobsRoutes.*

  private val jobs = TrieMap.empty[String, JobState]

  private def topicFor(id: String): String = s"job:$id"

  val routes: Route =
    concat(
      pathPrefix("jobs") {
        concat(
          pathEndOrSingleSlash {
            concat(
              get {
                CsrfSupport.withCsrfToken { csrf =>
                  complete(views.html.jobs(jobs.values.toVector.sortBy(_.startedAt).reverse, csrf))
                }
              },
              post {
                CsrfSupport.withCsrfToken { csrf =>
                  CsrfSupport.requireCsrf(csrf) {
                    val id = UUID.randomUUID().toString.take(8)
                    start(id, defaultSteps)
                    redirect(s"/jobs/$id", StatusCodes.SeeOther)
                  }
                }
              }
            )
          },
          path(Segment) { id =>
            get {
              CsrfSupport.withCsrfToken { csrf =>
                extractRequest { req =>
                  val scheme = if req.uri.scheme == "https" then "wss" else "ws"
                  val wsUrl  = s"$scheme://${req.uri.authority}/streams/jobs/$id"
                  val state  = jobs.getOrElse(id, JobState(id, 0, defaultSteps, Instant.now()))
                  complete(views.html.job(state, csrf, wsUrl))
                }
              }
            }
          }
        )
      },
      path("streams" / "jobs" / Segment) { id =>
        val outbound: Source[Message, NotUsed] =
          bus.subscribe(topicFor(id)).map(html => TextMessage(html))
        val flow = Flow.fromSinkAndSourceCoupled(Sink.ignore, outbound)
        handleWebSocketMessages(flow)
      }
    )

  /** Kick off the worker. Runs on the system dispatcher; multi-job is free. */
  private[hotwire] def start(id: String, totalSteps: Int): Unit =
    val state = JobState(id, 0, totalSteps, Instant.now())
    jobs.update(id, state)

    Source.tick(0.millis, tickInterval, ())
      .scan(0)((n, _) => n + 1)
      .takeWhile(_ <= totalSteps, inclusive = true)
      .runForeach { step =>
        jobs.update(id, state.copy(step = step))
        val frag =
          if step < totalSteps then
            TurboStream.stream(
              TurboStream.Action.Update,
              s"job-$id-progress",
              views.html._progress(id, step, totalSteps)
            )
          else
            TurboStream.stream(
              TurboStream.Action.Update,
              s"job-$id-progress",
              views.html._progress_done(id)
            )
        bus.publish(topicFor(id), frag)
      }
    ()

object JobsRoutes:
  final case class JobState(id: String, step: Int, total: Int, startedAt: Instant):
    def percent: Int =
      if total <= 0 then 100
      else math.min(100, math.round(step.toDouble / total.toDouble * 100).toInt)
    def done: Boolean = step >= total
