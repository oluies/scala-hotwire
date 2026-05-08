package hotwire

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.BoundedSourceQueue
import org.apache.pekko.stream.scaladsl.{BroadcastHub, Keep, Sink, Source}

import java.util.concurrent.ConcurrentHashMap
import java.util.function.{Function => JFunction}
import scala.concurrent.Future

/** In-process pub/sub. One [[Source.queue]] (FIFO from each publisher's point of view)
  * per topic, fanned out via [[BroadcastHub]]. Hubs are created lazily on first publish
  * or subscribe.
  *
  * Why not [[org.apache.pekko.stream.scaladsl.MergeHub]] for fan-in: each call to
  * `Source.single(...).runWith(mergeSink)` materialises an independent stream, and the
  * order in which two such streams push their first element to the MergeHub is
  * non-deterministic. `Source.queue` keeps ordering: two `offer` calls from the same
  * thread are FIFO into the underlying queue.
  *
  * An "anchor" `Sink.ignore` consumer is attached so the BroadcastHub keeps draining
  * with zero real subscribers; otherwise the upstream queue would back up to its
  * buffer limit and start dropping (dropNew on a [[BoundedSourceQueue]]).
  */
final class InProcessBroadcastBus(perTopicBufferSize: Int = 64)(using system: ActorSystem[?])
    extends BroadcastBus:

  private final case class Hub(queue: BoundedSourceQueue[String], source: Source[String, NotUsed])

  private val hubs = new ConcurrentHashMap[String, Hub]()

  private def buildHub(): Hub =
    val (queue, source) =
      Source.queue[String](perTopicBufferSize)
        .toMat(BroadcastHub.sink[String](bufferSize = perTopicBufferSize))(Keep.both)
        .run()
    source.runWith(Sink.ignore) // anchor; see class doc
    Hub(queue, source)

  private val builder: JFunction[String, Hub] =
    (_: String) => buildHub()

  private def hubFor(topic: String): Hub =
    hubs.computeIfAbsent(topic, builder)

  override def publish(topic: String, html: String): Unit =
    hubFor(topic).queue.offer(html)
    ()

  override def subscribe(topic: String): Source[String, NotUsed] =
    hubFor(topic).source

  override def shutdown(): Future[Unit] =
    hubs.values().forEach(_.queue.complete())
    Future.successful(())
