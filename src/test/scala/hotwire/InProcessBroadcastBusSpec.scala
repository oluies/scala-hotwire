package hotwire

import munit.FunSuite
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.testkit.TestSubscriber
import org.apache.pekko.stream.testkit.scaladsl.TestSink

import java.util.UUID
import scala.concurrent.duration.*

class InProcessBroadcastBusSpec extends FunSuite:

  given system: ActorSystem[Nothing] =
    ActorSystem(Behaviors.empty, "InProcessBroadcastBusSpec")

  override def afterAll(): Unit =
    system.terminate()

  /** Materialise a subscriber and synchronise with the BroadcastHub by publishing a
    * unique sentinel until it round-trips. After this returns, subsequent publishes
    * on the topic are guaranteed to reach the probe.
    *
    * Without this dance the test is racy: `runWith` returns before the new consumer
    * is registered with the BroadcastHub, so a publish that happens right after can
    * be delivered only to the anchor consumer. Local hardware happens to win the
    * race; CI's slower runner doesn't.
    *
    * Each probe filters out sentinels that aren't its own, so a later
    * `hotSubscribe` on the same topic can't pollute an earlier probe's stream
    * with that-probe's sync messages.
    */
  private val SentinelPrefix = "__sync__"

  private def hotSubscribe(bus: BroadcastBus, topic: String): TestSubscriber.Probe[String] =
    val mine     = SentinelPrefix + UUID.randomUUID().toString
    val probe    = bus.subscribe(topic)
      .filterNot(s => s.startsWith(SentinelPrefix) && s != mine)
      .runWith(TestSink[String]())
    probe.request(Long.MaxValue / 2)

    val deadline = System.currentTimeMillis + 5000
    var synced   = false
    while !synced && System.currentTimeMillis < deadline do
      bus.publish(topic, mine)
      try
        probe.expectNext(200.millis, mine)
        synced = true
      catch case _: AssertionError => ()

    if !synced then sys.error(s"Failed to sync subscriber on '$topic' within 5s")

    // Drain any duplicates of our own sentinel still in flight.
    var draining = true
    while draining do
      try probe.expectNext(50.millis, mine)
      catch case _: AssertionError => draining = false

    probe

  test("subscriber receives messages in publish order") {
    val bus   = new InProcessBroadcastBus()
    val probe = hotSubscribe(bus, "topic.a")

    bus.publish("topic.a", "<turbo-stream>1</turbo-stream>")
    bus.publish("topic.a", "<turbo-stream>2</turbo-stream>")

    probe.expectNext(3.seconds, "<turbo-stream>1</turbo-stream>")
    probe.expectNext(3.seconds, "<turbo-stream>2</turbo-stream>")
    probe.cancel()
  }

  test("topics are isolated") {
    val bus = new InProcessBroadcastBus()
    val a   = hotSubscribe(bus, "a")
    val b   = hotSubscribe(bus, "b")

    bus.publish("a", "ax")
    bus.publish("b", "bx")

    a.expectNext(3.seconds, "ax")
    b.expectNext(3.seconds, "bx")
    a.expectNoMessage(200.millis)
    b.expectNoMessage(200.millis)
    a.cancel(); b.cancel()
  }

  test("multiple subscribers on the same topic each receive every message") {
    val bus = new InProcessBroadcastBus()
    val s1  = hotSubscribe(bus, "t")
    val s2  = hotSubscribe(bus, "t")

    bus.publish("t", "hello")

    s1.expectNext(3.seconds, "hello")
    s2.expectNext(3.seconds, "hello")
    s1.cancel(); s2.cancel()
  }

  test("subscriber does NOT receive messages published before subscription") {
    val bus = new InProcessBroadcastBus()
    bus.publish("t2", "early") // anchor consumer drains; no subscriber yet

    val probe = hotSubscribe(bus, "t2")
    probe.expectNoMessage(200.millis) // 'early' is gone

    bus.publish("t2", "late")
    probe.expectNext(3.seconds, "late")
    probe.cancel()
  }
