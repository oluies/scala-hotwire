package hotwire.examples.jetstream

import hotwire.{ChatRoutes, CsrfSupport, TurboStream, TwirlSupport}
import hotwire.examples.jetstream.ReplayChatRoutes.SeqStamping
import org.apache.pekko.NotUsed
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}

import java.time.Instant
import java.util.UUID

/** Same demo as [[hotwire.ChatRoutes]], but built on a [[ReplayableBroadcastBus]].
  *
  * Routes are mounted under `/jetstream-chat/<room>` so the page coexists with the
  * core-NATS demo. Three things are different from the simpler chat:
  *
  *   1. Every `<turbo-stream>` fragment that reaches the browser carries a
  *      `data-seq="N"` attribute, where N is the JetStream stream sequence assigned
  *      by the broker. That includes both the synchronous form-POST response and
  *      the WS broadcast — see [[SeqStamping.stamp]].
  *
  *   2. The WS upgrade requires `?last_seq=N` (always sent by the client). N=0
  *      means "from the start of the retention window" — a fresh tab backfills
  *      the entire retention window via the WebSocket. N>0 means "strictly after
  *      N" — a reconnecting tab backfills only what it missed and then transitions
  *      to live tail on the same subscription.
  *
  *   3. There is no server-side message history. The page renders empty and is
  *      filled by the WS replay, so the broker is the single source of truth for
  *      what each tab has seen. This avoids a class of bugs where the rendered
  *      history (per-node, per-process) and the seq pointer (broker-wide) drift
  *      apart and cause silent message loss — see the bug_001 follow-up in
  *      `reviewmemory/pr_ultrareview4.md`.
  *
  * The `<room>` URL segment is restricted to `[a-zA-Z0-9_-]+` because NATS treats
  * `*` and `>` as wildcard characters in subject filters. Without this guard a
  * caller could open `/jetstream-streams/chat/%3E?last_seq=0` and have the bus
  * subscribe to `jschat.>`, exfiltrating every retained message in every room.
  */
final class ReplayChatRoutes(bus: ReplayableBroadcastBus):
  import ChatRoutes.ChatMessage
  import TwirlSupport.given

  private val Room = "[a-zA-Z0-9_-]+".r

  private def topicFor(room: String): String = s"jschat:$room"

  val routes: Route =
    concat(
      pathPrefix("jetstream-chat" / Room) { room =>
        concat(
          pathEndOrSingleSlash {
            get {
              CsrfSupport.withCsrfToken { csrf =>
                extractRequest { req =>
                  val scheme = if req.uri.scheme == "https" then "wss" else "ws"
                  val wsUrl  = s"$scheme://${req.uri.authority}/jetstream-streams/chat/$room"
                  complete(views.html.jetstream.chat(csrf, room, wsUrl))
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

                      val fragment = TurboStream.stream(
                        action = TurboStream.Action.Append,
                        target = "messages",
                        content = views.html._message(msg)
                      )

                      val seq     = bus.publishAndAck(topicFor(room), fragment)
                      val stamped = SeqStamping.stamp(fragment, seq)

                      import TurboStream.given
                      complete(stamped)
                    }
                  }
                }
              }
            }
          }
        )
      },
      path("jetstream-streams" / "chat" / Room) { room =>
        parameter("last_seq".as[Long].optional) { lastSeq =>
          // Negative values resolve to live tail rather than a JetStream
          // subscribe failure. The bus also defends against this; rejecting
          // at the route boundary avoids spinning up an ephemeral consumer
          // just to have it fail.
          val sanitisedLastSeq = lastSeq.filter(_ >= 0L)

          val frames: Source[(Long, String), NotUsed] =
            bus.subscribeFrom(topicFor(room), sanitisedLastSeq)

          val outbound: Source[Message, NotUsed] =
            frames.map { case (seq, html) => TextMessage(SeqStamping.stamp(html, seq)) }

          val flow = Flow.fromSinkAndSourceCoupled(Sink.ignore, outbound)
          handleWebSocketMessages(flow)
        }
      }
    )

  private def sanitise(s: String): String =
    s.filter(c => !c.isControl || c == '\n').trim

object ReplayChatRoutes:

  /** Inserts a `data-seq="N"` attribute on the *first* `<turbo-stream` element of an
    * already-rendered fragment. The fragment is generated by our own
    * [[hotwire.TurboStream]] helper so the structure is predictable: it always starts
    * with the literal `<turbo-stream ` (note the trailing space).
    *
    * Idempotent: if a `data-seq="…"` is already present it is left untouched. */
  object SeqStamping:
    private val needle = "<turbo-stream "

    def stamp(fragment: String, seq: Long): String =
      val idx = fragment.indexOf(needle)
      if idx < 0 then fragment
      else
        val tagEnd = fragment.indexOf('>', idx + needle.length)
        // Look for an existing data-seq= only inside the opening tag's attribute
        // list. Checking the whole fragment would also match user-controlled
        // content inside <template>…</template>, since Twirl HTML-escapes
        // <, >, &, and quotes but not '=' or word characters.
        val alreadyStamped =
          tagEnd >= 0 && fragment.substring(idx, tagEnd).contains("data-seq=")
        if alreadyStamped then fragment
        else
          val insertAt = idx + needle.length
          val (before, after) = fragment.splitAt(insertAt)
          s"""${before}data-seq="$seq" $after"""
