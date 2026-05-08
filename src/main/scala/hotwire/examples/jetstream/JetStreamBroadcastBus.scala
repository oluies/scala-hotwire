package hotwire.examples.jetstream

import io.nats.client.api.{
  AckPolicy,
  ConsumerConfiguration,
  DeliverPolicy,
  RetentionPolicy,
  StorageType,
  StreamConfiguration
}
import io.nats.client.{
  Connection,
  Dispatcher,
  JetStream,
  JetStreamApiException,
  Message,
  MessageHandler,
  Nats,
  Options,
  PublishOptions,
  PushSubscribeOptions
}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.{BroadcastHub, Keep, Sink, Source, SourceQueueWithComplete}

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.Future

/** Cross-node pub/sub backed by NATS JetStream with reconnect-replay.
  *
  * Differs from the core-NATS bus in two ways:
  *
  *   1. `publishAndAck` blocks for the broker ack so the caller learns the persistent
  *      stream sequence. The HTTP turbo-stream response can then carry that seq back to
  *      the submitter, who stores it in `localStorage`.
  *
  *   2. `subscribeFrom(topic, Some(n))` opens an *ephemeral* push consumer with
  *      [[DeliverPolicy.ByStartSequence]] starting at `n + 1`, so a browser tab that
  *      reconnects after a network blip backfills the messages it missed before
  *      switching to the live tail. No server-side per-tab state is needed; the
  *      browser is the source of truth for "what have I seen".
  *
  * The same [[org.apache.pekko.stream.scaladsl.BroadcastHub]] + dropHead queue shape
  * as the core-NATS bus is used to insulate JetStream's dispatcher thread from a
  * slow downstream Pekko subscriber.
  *
  * Stream layout: a single JetStream stream covers `chat.>` with limits-based
  * retention. Per-subject limits are set so each room keeps its own bounded history.
  */
final class JetStreamBroadcastBus(
    connection: Connection,
    streamName: String,
    perTopicBufferSize: Int = 64
)(using system: ActorSystem[?])
    extends ReplayableBroadcastBus:

  private given ec: scala.concurrent.ExecutionContext = system.executionContext

  private val js: JetStream                = connection.jetStream()

  private final case class LiveHub(
      dispatcher: Dispatcher,
      queue: SourceQueueWithComplete[(Long, String)],
      source: Source[(Long, String), NotUsed]
  )

  /** Live-tail hubs are cached per topic so multiple browser tabs share one consumer.
    * Replay subscribers each get their own ephemeral consumer (no caching) because
    * each tab carries a different last_seq. */
  private val liveHubs = new ConcurrentHashMap[String, LiveHub]()

  private def topicToSubject(topic: String): String =
    topic.replace(':', '.').replace('/', '.')

  private def buildLiveHub(topic: String): LiveHub =
    val (queue, raw) =
      Source.queue[(Long, String)](perTopicBufferSize, OverflowStrategy.dropHead)
        .toMat(BroadcastHub.sink[(Long, String)](bufferSize = perTopicBufferSize))(Keep.both)
        .run()

    raw.runWith(Sink.ignore) // anchor

    val subject    = topicToSubject(topic)
    val dispatcher = connection.createDispatcher()

    val handler: MessageHandler = (msg: Message) =>
      val seq     = msg.metaData().streamSequence()
      val payload = new String(msg.getData, StandardCharsets.UTF_8)
      queue.offer((seq, payload))
      ()

    val pso = PushSubscribeOptions.builder()
      .stream(streamName)
      .configuration(
        ConsumerConfiguration.builder()
          .deliverPolicy(DeliverPolicy.New)
          .ackPolicy(AckPolicy.None)
          .build()
      )
      .build()

    js.subscribe(subject, dispatcher, handler, /* autoAck = */ false, pso)
    LiveHub(dispatcher, queue, raw)

  private def liveHubFor(topic: String): LiveHub =
    liveHubs.computeIfAbsent(topic, t => buildLiveHub(t))

  /** Ephemeral, per-tab JetStream consumer that backfills from `lastSeq + 1` and then
    * continues delivering live messages on the same subscription. Each subscriber
    * gets its own consumer (no BroadcastHub) because each tab has a different start
    * point — fan-out caching only makes sense for the live tail. */
  private def replaySource(topic: String, lastSeq: Long): Source[(Long, String), NotUsed] =
    val subject = topicToSubject(topic)

    Source
      .queue[(Long, String)](perTopicBufferSize, OverflowStrategy.dropHead)
      .mapMaterializedValue { queue =>
        val dispatcher = connection.createDispatcher()
        val handler: MessageHandler = (msg: Message) =>
          val seq     = msg.metaData().streamSequence()
          val payload = new String(msg.getData, StandardCharsets.UTF_8)
          queue.offer((seq, payload))
          ()

        val pso = PushSubscribeOptions.builder()
          .stream(streamName)
          .configuration(
            ConsumerConfiguration.builder()
              .deliverPolicy(DeliverPolicy.ByStartSequence)
              .startSequence(lastSeq + 1L)
              .ackPolicy(AckPolicy.None)
              .build()
          )
          .build()

        try
          js.subscribe(subject, dispatcher, handler, /* autoAck = */ false, pso)
          queue.watchCompletion().onComplete { _ =>
            try connection.closeDispatcher(dispatcher)
            catch case _: Throwable => ()
          }
        catch
          case t: Throwable =>
            queue.fail(t)

        NotUsed
      }

  override def publish(topic: String, html: String): Unit =
    publishAndAck(topic, html)
    ()

  override def publishAndAck(topic: String, html: String): Long =
    val subject = topicToSubject(topic)
    val opts    = PublishOptions.builder().expectedStream(streamName).build()
    val ack     = js.publish(subject, html.getBytes(StandardCharsets.UTF_8), opts)
    ack.getSeqno

  override def subscribe(topic: String): Source[String, NotUsed] =
    subscribeFrom(topic, None).map(_._2)

  override def subscribeFrom(
      topic: String,
      lastSeq: Option[Long]
  ): Source[(Long, String), NotUsed] =
    lastSeq match
      case None    => liveHubFor(topic).source
      case Some(n) => replaySource(topic, n)

  override def shutdown(): Future[Unit] = Future {
    liveHubs.values().forEach { h =>
      try connection.closeDispatcher(h.dispatcher)
      catch case _: Throwable => ()
      h.queue.complete()
    }
    try connection.flush(Duration.ofSeconds(2))
    catch case _: Throwable => ()
    connection.close()
  }

object JetStreamBroadcastBus:

  /** Connect and ensure the JetStream stream exists with sane chat-style retention.
    * Idempotent: if a stream of this name already exists with a compatible config it
    * is updated in place; otherwise it is created. */
  def connectAndEnsureStream(
      url: String = Options.DEFAULT_URL,
      streamName: String = "CHAT",
      subjectsWildcard: String = "chat.>",
      maxMsgsPerSubject: Long = 1000L,
      maxAge: Duration = Duration.ofHours(24)
  ): Connection =
    val connection = Nats.connect(url)
    val jsm        = connection.jetStreamManagement()
    val cfg = StreamConfiguration.builder()
      .name(streamName)
      .subjects(subjectsWildcard)
      .retentionPolicy(RetentionPolicy.Limits)
      .maxMessagesPerSubject(maxMsgsPerSubject)
      .maxAge(maxAge)
      .storageType(StorageType.File)
      .build()

    try jsm.addStream(cfg)
    catch
      case _: JetStreamApiException =>
        // Already exists — update in place. Differences in retention/limits are
        // applied without losing existing messages.
        try jsm.updateStream(cfg)
        catch case _: JetStreamApiException => ()

    connection
