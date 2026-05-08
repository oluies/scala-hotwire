package hotwire

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.Future

/** Pub/sub façade that the rest of the app talks to.
  *
  * Two implementations ship out of the box:
  *   - [[InProcessBroadcastBus]] using Pekko Streams MergeHub/BroadcastHub. Single-node, zero ops.
  *   - [[NatsBroadcastBus]]      using core NATS pub/sub. Multi-node, polyglot publishers.
  *
  * The contract is intentionally tiny so the swap from one to the other is one DI change.
  *
  * Subscribers only receive frames published *after* their subscription. Reconnect-replay
  * is out of scope for this façade — wire JetStream durable consumers behind a different
  * implementation if you need it.
  */
trait BroadcastBus:

  /** Publish a raw HTML fragment (one or more `<turbo-stream>` elements) on `topic`.
    * Fire-and-forget. Delivery to remote subscribers is best-effort. */
  def publish(topic: String, html: String): Unit

  /** A `Source` that emits every message published on `topic` from the moment it
    * is materialised. Multiple materialisations of the same topic are independent. */
  def subscribe(topic: String): Source[String, NotUsed]

  /** Release transport resources (NATS connection, hub anchors). Idempotent. */
  def shutdown(): Future[Unit]
