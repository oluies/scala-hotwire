package hotwire

import hotwire.TwirlSupport.given
import org.apache.pekko.NotUsed
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.collection.concurrent.TrieMap

/** Demo chat room demonstrating Hotwire Turbo Streams over Pekko HTTP.
  *
  * Two Hotwire mechanisms are in play:
  *
  *   1. Form POST → `text/vnd.turbo-stream.html` HTTP response.
  *      The submitter receives the new message rendered as a `<turbo-stream action="append">`
  *      and Turbo splices it into `#messages` without a page reload.
  *
  *   2. WebSocket fan-out via [[BroadcastBus]].
  *      `<turbo-stream-source>` opens a WS to `/streams/chat/<room>`. The same
  *      `<turbo-stream>` fragment that was returned to the submitter is also published
  *      on the bus, and every other tab subscribed to the room receives it as a text
  *      WS frame, which Turbo renders into its DOM.
  */
final class ChatRoutes(bus: BroadcastBus):
  import ChatRoutes.*

  // Demo storage. In a real app this is your DB.
  private val rooms = TrieMap.empty[String, Vector[ChatMessage]]
  private def history(room: String): Vector[ChatMessage] =
    rooms.getOrElse(room, Vector.empty)
  private def append(room: String, msg: ChatMessage): Unit =
    rooms.updateWith(room)(prev => Some(prev.getOrElse(Vector.empty) :+ msg))
    ()

  private def topicFor(room: String): String = s"chat:$room"

  val routes: Route =
    concat(
      pathPrefix("chat" / Segment) { room =>
        concat(
          pathEndOrSingleSlash {
            get {
              CsrfSupport.withCsrfToken { csrf =>
                extractRequest { req =>
                  val scheme = if req.uri.scheme == "https" then "wss" else "ws"
                  val wsUrl  = s"$scheme://${req.uri.authority}/streams/chat/$room"
                  complete(views.html.chat(history(room), csrf, room, wsUrl))
                }
              }
            }
          },
          path("messages") {
            post {
              CsrfSupport.withCsrfToken { csrf =>
                CsrfSupport.requireCsrf(csrf) {
                  formField("author") { author =>
                    formField("body") { body =>
                      val msg = ChatMessage(
                        id = UUID.randomUUID().toString.take(8),
                        author = sanitise(author).take(40),
                        body = sanitise(body).take(500),
                        timestamp = Instant.now()
                      )
                      append(room, msg)

                      val fragment = TurboStream.stream(
                        action = TurboStream.Action.Append,
                        target = "messages",
                        content = views.html._message(msg)
                      )

                      // Broadcast to all subscribed tabs (including this one — Turbo
                      // dedupes by element id, so a duplicate append is harmless).
                      bus.publish(topicFor(room), fragment)

                      // Synchronous turbo-stream response for the submitter.
                      import TurboStream.given
                      complete(fragment)
                    }
                  }
                }
              }
            }
          }
        )
      },
      path("streams" / "chat" / Segment) { room =>
        val outbound: Source[Message, NotUsed] =
          bus.subscribe(topicFor(room)).map(html => TextMessage(html))

        // Turbo's <turbo-stream-source> never sends to the server; just drain inbound.
        val flow = Flow.fromSinkAndSourceCoupled(Sink.ignore, outbound)
        handleWebSocketMessages(flow)
      }
    )

  /** Strip control chars; HTML escaping happens in the Twirl template. */
  private def sanitise(s: String): String =
    s.filter(c => !c.isControl || c == '\n').trim

object ChatRoutes:
  final case class ChatMessage(
      id: String,
      author: String,
      body: String,
      timestamp: Instant
  ):
    def displayTime: String =
      DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(java.time.ZoneId.systemDefault())
        .format(timestamp)
