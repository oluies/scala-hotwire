package hotwire

import munit.FunSuite
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.testkit.scaladsl.TestSink

import scala.concurrent.duration.*

class InProcessBroadcastBusSpec extends FunSuite:

  given system: ActorSystem[Nothing] =
    ActorSystem(Behaviors.empty, "InProcessBroadcastBusSpec")

  override def afterAll(): Unit =
    system.terminate()

  test("subscriber receives messages published after subscription") {
    val bus  = new InProcessBroadcastBus()
    val probe = bus.subscribe("topic.a").runWith(TestSink[String]())
    probe.request(2)

    bus.publish("topic.a", "<turbo-stream>1</turbo-stream>")
    bus.publish("topic.a", "<turbo-stream>2</turbo-stream>")

    probe.expectNext(3.seconds, "<turbo-stream>1</turbo-stream>")
    probe.expectNext(3.seconds, "<turbo-stream>2</turbo-stream>")
    probe.cancel()
  }

  test("topics are isolated") {
    val bus = new InProcessBroadcastBus()
    val a = bus.subscribe("a").runWith(TestSink[String]())
    val b = bus.subscribe("b").runWith(TestSink[String]())
    a.request(1); b.request(1)

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
    val s1 = bus.subscribe("t").runWith(TestSink[String]())
    val s2 = bus.subscribe("t").runWith(TestSink[String]())
    s1.request(1); s2.request(1)

    bus.publish("t", "hello")

    s1.expectNext(3.seconds, "hello")
    s2.expectNext(3.seconds, "hello")
    s1.cancel(); s2.cancel()
  }

  test("subscriber does NOT receive messages published before subscription") {
    val bus = new InProcessBroadcastBus()
    bus.publish("t2", "early") // anchor consumer drains; no subscriber yet

    val probe = bus.subscribe("t2").runWith(TestSink[String]())
    probe.request(1)
    probe.expectNoMessage(200.millis) // 'early' is gone

    bus.publish("t2", "late")
    probe.expectNext(3.seconds, "late")
    probe.cancel()
  }
