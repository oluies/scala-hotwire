package hotwire.examples.jetstream

import hotwire.BroadcastBus
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

/** Bus that exposes a per-message monotonic sequence and lets a subscriber resume from
  * a specific point in the stream. Used by the JetStream replay example so a browser
  * tab that drops its WebSocket can reconnect with `?last_seq=N` and backfill.
  *
  * Implementations must guarantee:
  *   - sequences are strictly monotonic per topic
  *   - `subscribeFrom(topic, Some(n))` delivers every message with seq > n that is
  *     still retained by the backing store, in order
  *   - `subscribeFrom(topic, None)` is equivalent to [[BroadcastBus.subscribe]] (live tail)
  *
  * The base [[BroadcastBus]] contract is preserved so non-replay callers can ignore the
  * sequence entirely.
  */
trait ReplayableBroadcastBus extends BroadcastBus:

  /** Publish and wait for the backing store to ack with a sequence number.
    *
    * Unlike [[BroadcastBus.publish]] this is *not* fire-and-forget — it blocks the
    * calling thread (or the Future, in async callers) until the broker confirms
    * persistence. The returned seq is the value subsequently visible on
    * [[subscribeFrom]] for this message. */
  def publishAndAck(topic: String, html: String): Long

  /** Subscribe starting strictly *after* `lastSeq`. `None` means "live tail from now",
    * matching the semantics of plain [[BroadcastBus.subscribe]]. `Some(0)` means
    * "from the start of the retention window" — the bus will deliver every
    * message the backing store still holds. */
  def subscribeFrom(topic: String, lastSeq: Option[Long]): Source[(Long, String), NotUsed]
