package hotwire

import io.nats.client.{Connection, Dispatcher, Nats, Options}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.{BroadcastHub, Keep, Sink, Source, SourceQueueWithComplete}

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.function.{Function => JFunction}
import scala.concurrent.Future

/** Cross-node pub/sub backed by NATS core (no JetStream). Each topic maps to a NATS
  * subject; payload is the raw HTML bytes of the `<turbo-stream>` fragment.
  *
  * On the publish side: [[Connection.publish]] is non-blocking and internally buffered
  * by jnats.
  *
  * On the subscribe side: a single jnats Dispatcher per topic enqueues callback messages
  * into a Pekko [[SourceQueueWithComplete]] feeding a [[BroadcastHub]]. The queue uses
  * `OverflowStrategy.dropHead` so a slow Pekko subscriber chain cannot stall the NATS
  * dispatcher thread — the oldest pending stream frame is dropped instead.
  *
  * Topic mapping: `':'` and `'/'` in the application topic become `'.'` so that
  * application topics like `chat:lobby` become valid NATS subjects (`chat.lobby`).
  */
final class NatsBroadcastBus(
    connection: Connection,
    perTopicBufferSize: Int = 64
)(using system: ActorSystem[?])
    extends BroadcastBus:

  private given ec: scala.concurrent.ExecutionContext = system.executionContext

  private final case class Hub(
      dispatcher: Dispatcher,
      queue: SourceQueueWithComplete[String],
      source: Source[String, NotUsed]
  )

  private val hubs = new ConcurrentHashMap[String, Hub]()

  private def topicToSubject(topic: String): String =
    topic.replace(':', '.').replace('/', '.')

  private def buildHub(topic: String): Hub =
    val (queue, raw) =
      Source.queue[String](perTopicBufferSize, OverflowStrategy.dropHead)
        .toMat(BroadcastHub.sink[String](bufferSize = perTopicBufferSize))(Keep.both)
        .run()

    raw.runWith(Sink.ignore) // anchor

    val subject = topicToSubject(topic)
    val dispatcher = connection.createDispatcher { msg =>
      val payload = new String(msg.getData, StandardCharsets.UTF_8)
      // Non-blocking offer; on full buffer the dropHead strategy drops the oldest entry.
      queue.offer(payload)
      ()
    }
    dispatcher.subscribe(subject)
    Hub(dispatcher, queue, raw)

  private val builder: JFunction[String, Hub] =
    (t: String) => buildHub(t)

  private def hubFor(topic: String): Hub =
    hubs.computeIfAbsent(topic, builder)

  override def publish(topic: String, html: String): Unit =
    connection.publish(topicToSubject(topic), html.getBytes(StandardCharsets.UTF_8))

  override def subscribe(topic: String): Source[String, NotUsed] =
    hubFor(topic).source

  override def shutdown(): Future[Unit] = Future {
    hubs.values().forEach { h =>
      try connection.closeDispatcher(h.dispatcher)
      catch case _: Throwable => ()
      h.queue.complete()
    }
    try connection.flush(Duration.ofSeconds(2))
    catch case _: Throwable => ()
    connection.close()
  }

object NatsBroadcastBus:
  /** Convenience: build a NATS connection at `url` (default `nats://localhost:4222`). */
  def connect(url: String = Options.DEFAULT_URL): Connection =
    Nats.connect(url)
