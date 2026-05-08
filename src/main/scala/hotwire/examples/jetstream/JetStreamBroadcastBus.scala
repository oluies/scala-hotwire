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
  JetStreamManagement,
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
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.Future

/** Cross-node pub/sub backed by NATS JetStream with reconnect-replay.
  *
  * Differs from the core-NATS bus in two ways:
  *
  *   1. `publishAndAck` blocks for the broker ack so the caller learns the persistent
  *      stream sequence. The HTTP turbo-stream response can then carry that seq back to
  *      the submitter, who stores it in the browser's session storage.
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
  * Stream layout: a single JetStream stream covers the configured subjects wildcard
  * (`jschat.>` by default — see [[JetStreamBroadcastBus.connectAndEnsureStream]])
  * with limits-based retention. Per-subject limits keep each room bounded.
  */
final class JetStreamBroadcastBus(
    connection: Connection,
    streamName: String,
    perTopicBufferSize: Int = 64
)(using system: ActorSystem[?])
    extends ReplayableBroadcastBus:

  private given ec: scala.concurrent.ExecutionContext = system.executionContext

  private val js: JetStream            = connection.jetStream()
  private val jsm: JetStreamManagement = connection.jetStreamManagement()

  private final case class LiveHub(
      dispatcher: Dispatcher,
      queue: SourceQueueWithComplete[(Long, String)],
      source: Source[(Long, String), NotUsed]
  )

  /** Live-tail hubs are cached per topic so multiple browser tabs share one consumer.
    * Replay subscribers each get their own ephemeral consumer (no caching) because
    * each tab carries a different last_seq. */
  private val liveHubs = new ConcurrentHashMap[String, LiveHub]()

  /** In-memory cache of "highest seq we have observed per topic", populated by the
    * publish path and any active push consumer. The renderer reads this for the
    * `data-initial-seq` attribute on the chat page; on a cold node where neither
    * a local POST nor an active subscriber has bumped this yet, [[latestSeq]]
    * falls back to a JetStream stream-info round-trip. */
  private val seqCache = new ConcurrentHashMap[String, AtomicLong]()

  private def topicToSubject(topic: String): String =
    topic.replace(':', '.').replace('/', '.')

  private def cachedSeq(topic: String): AtomicLong =
    seqCache.computeIfAbsent(topic, _ => new AtomicLong(0L))

  private def bump(topic: String, seq: Long): Unit =
    cachedSeq(topic).updateAndGet(prev => math.max(prev, seq))
    ()

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
      bump(topic, seq)
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

    try
      js.subscribe(subject, dispatcher, handler, /* autoAck = */ false, pso)
      LiveHub(dispatcher, queue, raw)
    catch
      case t: Throwable =>
        // Roll back resources before propagating, otherwise the running stream
        // and dispatcher thread leak (computeIfAbsent does not cache the failed
        // mapping but cannot clean up what the lambda allocated).
        try connection.closeDispatcher(dispatcher) catch case _: Throwable => ()
        queue.complete()
        throw t

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

        // Register cleanup *before* js.subscribe so a subscribe failure that
        // surfaces via queue.fail(t) still triggers the dispatcher close.
        queue.watchCompletion().onComplete { _ =>
          try connection.closeDispatcher(dispatcher)
          catch case _: Throwable => ()
        }

        val handler: MessageHandler = (msg: Message) =>
          val seq     = msg.metaData().streamSequence()
          val payload = new String(msg.getData, StandardCharsets.UTF_8)
          bump(topic, seq)
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

        try js.subscribe(subject, dispatcher, handler, /* autoAck = */ false, pso)
        catch case t: Throwable => queue.fail(t)

        NotUsed
      }

  override def publish(topic: String, html: String): Unit =
    publishAndAck(topic, html)
    ()

  override def publishAndAck(topic: String, html: String): Long =
    val subject = topicToSubject(topic)
    val opts    = PublishOptions.builder().expectedStream(streamName).build()
    val ack     = js.publish(subject, html.getBytes(StandardCharsets.UTF_8), opts)
    val seq     = ack.getSeqno
    bump(topic, seq)
    seq

  override def subscribe(topic: String): Source[String, NotUsed] =
    subscribeFrom(topic, None).map(_._2)

  override def subscribeFrom(
      topic: String,
      lastSeq: Option[Long]
  ): Source[(Long, String), NotUsed] =
    lastSeq match
      case None                  => liveHubFor(topic).source
      case Some(n) if n < 0L     => liveHubFor(topic).source
      case Some(n)               => replaySource(topic, n)

  /** Highest known seq for `topic`. Returns the in-memory cache when populated
    * (bumped by every publish, live-tail delivery, and replay delivery on this
    * node), and otherwise falls back to a `getLastMessage` query against the
    * broker. Returns `0L` when there is no message on the subject (or the
    * broker query fails — callers should treat `0L` as "unknown, render with
    * data-initial-seq=0" rather than as a real seq). */
  override def latestSeq(topic: String): Long =
    val cached = cachedSeq(topic).get()
    if cached > 0L then cached
    else
      try
        val info = jsm.getLastMessage(streamName, topicToSubject(topic))
        val seq  = if info == null then 0L else info.getSeq
        bump(topic, seq)
        seq
      catch case _: Throwable => 0L

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
    * is updated in place; otherwise it is created.
    *
    * Defaults are picked to *not* collide with the subjects used by the plain
    * [[hotwire.NatsBroadcastBus]] (which writes on `chat.>`): the JetStream demo
    * uses its own subject namespace so the two buses can coexist on one NATS
    * server without one silently capturing the other's traffic. */
  def connectAndEnsureStream(
      url: String = Options.DEFAULT_URL,
      streamName: String = "CHAT_REPLAY",
      subjectsWildcard: String = "jschat.>",
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
