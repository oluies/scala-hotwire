# Plan: JetStream durable consumer + background-job progress bar

Two changes that exercise the parts of the pub/sub façade the existing demos
don't: **replay-on-reconnect** (a third `BroadcastBus` implementation) and
**non-HTTP-driven publishers** (a worker pushing progress frames into the bus
on its own clock).

References:
- NATS JetStream concepts: <https://docs.nats.io/nats-concepts/jetstream>
- jnats JetStream API: <https://github.com/nats-io/nats.java#jetstream>
- Existing TODO marker: `src/main/scala/hotwire/NatsBroadcastBus.scala` and
  `README.md` ("JetStream durable consumers" under "What's deliberately not here")

The two parts are *independent* — progress bars work fine on the existing
in-process bus. Doing JetStream first only matters if we want progress-bar
*reconnect replay* to work across nodes. Recommendation: do them in parallel
PRs and merge progress bar first.

---

## Part 1 — `JetStreamBroadcastBus`

### Why

The current `NatsBroadcastBus` uses **core NATS** pub/sub, which is at-most-once
and has no server-side buffering. If a browser tab closes the WebSocket and
reconnects 200ms later, every Turbo Stream frame published in that gap is
lost. For chat that's tolerable (the next message arrives soon). For a
progress bar or a long-running job notification it's not — the user reconnects
and sees a frozen 47% bar forever.

JetStream adds a persistent log per *stream* (capital-S NATS Stream, not
Turbo Stream — naming is unfortunate). A *durable consumer* identified by
name remembers the last delivered sequence per subject filter, so a Pekko
subscriber that disappears and comes back gets every missed message replayed
in order before tailing live.

### Architecture

Add a third `BroadcastBus` implementation; the trait stays unchanged.

```
JetStreamBroadcastBus(connection, streamName, replayHorizon)
  ├─ on construction: ensure StreamConfiguration exists (idempotent add)
  └─ subscribe(topic):
       ├─ derive durableName from (topic, subscriber-id)
       ├─ create PushSubscription with DeliverPolicy.ByStartTime(now - replayHorizon)
       ├─ feed messages into Source.queue (dropHead) → BroadcastHub
       └─ on Source completion: drainSubscription + delete consumer
```

Two design decisions to settle up front:

1. **Per-subscription durable consumer vs shared.** A *shared* durable would
   require ack coordination across all WS tabs — wrong fit. A *unique* durable
   per WS connection is the right shape: the consumer is created on
   `subscribe()`, deleted on stream completion. Name it
   `hotwire-<topic>-<uuid>`.

2. **Replay horizon.** Don't use `DeliverPolicy.All` — that would replay the
   entire chat history on every page load. Use
   `DeliverPolicy.ByStartTime(Instant.now().minus(replayHorizon))` with a
   default of e.g. 60s. That covers a typical reconnect, doesn't replay an
   hour of chat. Make it configurable per-instance.

### Code changes

1. **`build.sbt`** — jnats already includes JetStream client, no new
   dependency.

2. **`src/main/scala/hotwire/JetStreamBroadcastBus.scala`** (new file). Skeleton:

   ```scala
   package hotwire

   import io.nats.client.{Connection, JetStream, JetStreamSubscription, PushSubscribeOptions}
   import io.nats.client.api.{ConsumerConfiguration, DeliverPolicy, StreamConfiguration, RetentionPolicy, StorageType}
   import org.apache.pekko.NotUsed
   import org.apache.pekko.actor.typed.ActorSystem
   import org.apache.pekko.stream.OverflowStrategy
   import org.apache.pekko.stream.scaladsl.{BroadcastHub, Keep, Sink, Source, SourceQueueWithComplete}

   import java.nio.charset.StandardCharsets
   import java.time.Duration
   import java.util.UUID
   import scala.concurrent.Future

   /** JetStream-backed bus with bounded reconnect-replay window.
     *
     * On startup ensures a stream named `streamName` exists, capturing all
     * subjects under `subjectPrefix.>`. Every `subscribe(topic)` creates an
     * ephemeral-named *durable* consumer with start-time = now - replayHorizon,
     * deletes it on stream completion. Replay window is bounded so reconnects
     * don't replay all-of-history. */
   final class JetStreamBroadcastBus(
       connection: Connection,
       streamName: String = "hotwire",
       subjectPrefix: String = "hotwire",
       perTopicBufferSize: Int = 64,
       replayHorizon: Duration = Duration.ofSeconds(60)
   )(using system: ActorSystem[?])
       extends BroadcastBus:

     ensureStream()

     override def publish(topic: String, html: String): Unit =
       connection.publish(subjectFor(topic), html.getBytes(StandardCharsets.UTF_8))
       // js().publish would block for ack; for fire-and-forget core publish
       // already lands in the stream because the stream subscribes to the subject

     override def subscribe(topic: String): Source[String, NotUsed] = ???
     override def shutdown(): Future[Unit] = ???

     private def subjectFor(topic: String): String =
       s"$subjectPrefix.${topic.replace(':', '.').replace('/', '.')}"

     private def ensureStream(): Unit =
       val mgr = connection.jetStreamManagement()
       val cfg = StreamConfiguration.builder()
         .name(streamName)
         .subjects(s"$subjectPrefix.>")
         .retentionPolicy(RetentionPolicy.Limits)
         .storageType(StorageType.Memory) // OK for demo; switch to File for real
         .maxAge(replayHorizon.multipliedBy(10))
         .build()
       try mgr.addStream(cfg)
       catch case _: io.nats.client.JetStreamApiException =>
         mgr.updateStream(cfg) // already exists; reconcile
   ```

   Subscribe body — the interesting part:

   ```scala
   override def subscribe(topic: String): Source[String, NotUsed] =
     val (queue, raw) =
       Source.queue[String](perTopicBufferSize, OverflowStrategy.dropHead)
         .toMat(BroadcastHub.sink[String](bufferSize = perTopicBufferSize))(Keep.both)
         .run()
     raw.runWith(Sink.ignore) // anchor

     val durable = s"hotwire-${topic.replace(':', '-')}-${UUID.randomUUID().toString.take(8)}"
     val consumerCfg = ConsumerConfiguration.builder()
       .durable(durable)
       .deliverPolicy(DeliverPolicy.ByStartTime)
       .startTime(java.time.ZonedDateTime.now().minus(replayHorizon))
       .build()
     val opts = PushSubscribeOptions.builder()
       .stream(streamName)
       .configuration(consumerCfg)
       .build()

     val js = connection.jetStream()
     val dispatcher = connection.createDispatcher()
     val sub: JetStreamSubscription = js.subscribe(
       subjectFor(topic), dispatcher,
       msg => {
         queue.offer(new String(msg.getData, StandardCharsets.UTF_8))
         msg.ack()
       },
       /*autoAck=*/ false, opts
     )

     // when the BroadcastHub source completes (last subscriber gone), tear down.
     queue.watchCompletion().onComplete { _ =>
       try sub.unsubscribe() catch case _: Throwable => ()
       try connection.jetStreamManagement().deleteConsumer(streamName, durable)
       catch case _: Throwable => ()
     }(system.executionContext)

     raw
   ```

   Note: this *creates one durable per subscribe call*. The `BroadcastHub`
   then fans that single consumer's stream out to N HTTP/WS subscribers. So
   "one tab dies" doesn't drop the consumer; only "all tabs gone" does.
   That's a slight semantic change from `NatsBroadcastBus` (where each
   subscribe gets a fresh dispatcher). Acceptable — replay across tabs is
   pointless anyway.

3. **`src/main/scala/hotwire/Main.scala`** — selector:

   ```scala
   val busOpt: Option[BroadcastBus] = demo match
     case Demo.Posts => None
     case _ =>
       (natsUrl, sys.env.get("BUS").map(_.toLowerCase)) match
         case (Some(url), Some("jetstream")) =>
           log.info(s"Connecting to NATS JetStream at $url")
           Some(new JetStreamBroadcastBus(NatsBroadcastBus.connect(url)))
         case (Some(url), _) =>
           log.info(s"Connecting to NATS (core) at $url")
           Some(new NatsBroadcastBus(NatsBroadcastBus.connect(url)))
         case _ =>
           log.info("Using in-process broadcast bus")
           Some(new InProcessBroadcastBus())
   ```

   Document the new `BUS=jetstream` env var in README.

### Tests

JetStream needs a real broker — same situation as `NatsBroadcastBus`. Don't
add it to CI. Instead:

- Add a manual test recipe in README ("Verifying replay") that boots
  `nats-server -js`, runs the app with `BUS=jetstream NATS_URL=…`, sends N
  messages with the WS *closed*, opens the WS, asserts last N replayed.
- Optional unit-ish test using
  [`berlinguyinca/nats-server-embedded`](https://github.com/nats-io/nats-server)
  via testcontainers — only worth it if we add testcontainers anyway. Defer.

### Risks / open questions

- **`StorageType.Memory`** loses replay state on broker restart. Fine for the
  demo and matches "60s replay horizon" intent. For production: file
  storage. Make it configurable on the bus constructor.
- **Subject mapping collision with core `NatsBroadcastBus`.** The core bus
  uses `chat.lobby` directly; JetStream version prefixes with `hotwire.`. So
  a node running core and a node running JetStream against the same broker
  *don't* see each other's traffic. Document loudly, or align them by
  defaulting `subjectPrefix=""`. Recommendation: leave the prefix — it lets
  JetStream coexist with non-Hotwire NATS traffic.
- **Per-subscribe durable churn.** Each page reload creates a new durable
  consumer. JetStream cleans up via `InactiveThreshold` if we add it to the
  consumer config — set to 5 minutes. Otherwise we leak consumers when the
  app crashes before `unsubscribe()` runs.

---

## Part 2 — Background-job progress bar

### Why

The two existing demos are HTTP-driven publishers: a chat POST publishes a
frame, an HTTP GET renders a page. The strongest argument for the bus
abstraction is *non-HTTP* publishers — a cron job, a Pekko Streams pipeline,
a worker reading from another queue. None of that is shown.

A progress bar is the canonical such demo: kick off a job, watch a
percentage tick toward 100 in the browser without polling. The job is just
a `Source.tick` that publishes one Turbo Stream frame per tick. Zero
client-side JavaScript beyond Turbo itself.

### UX

```
GET  /jobs                  → list of jobs + "Start a job" form
POST /jobs                  → creates job(id), redirects to /jobs/:id, kicks off worker
GET  /jobs/:id              → renders progress page with <turbo-stream-source> + initial bar
WS   /streams/jobs/:id      → progress frames
```

Page content during a run:

```html
<turbo-frame id="job-42-progress">
  <progress value="47" max="100">47%</progress>
  <span>47/100 widgets reticulated</span>
</turbo-frame>
```

Each tick the worker publishes:

```html
<turbo-stream action="update" target="job-42-progress">
  <template>
    <progress value="48" max="100">48%</progress>
    <span>48/100 widgets reticulated</span>
  </template>
</turbo-stream>
```

On completion:

```html
<turbo-stream action="update" target="job-42-progress">
  <template>
    <p>Done. <a href="/jobs">Back to jobs</a>.</p>
  </template>
</turbo-stream>
```

### Code changes

1. **`src/main/scala/hotwire/JobsRoutes.scala`** (new):

   ```scala
   final class JobsRoutes(bus: BroadcastBus)(using system: ActorSystem[?]):
     private val jobs = TrieMap.empty[String, JobState]
     private def topicFor(id: String): String = s"job:$id"
     // … routes: GET /jobs, POST /jobs, GET /jobs/:id, WS /streams/jobs/:id
     // start(id): kick off Source.tick that ticks every 200ms, publishes
     //            update fragments, completes after N ticks.
   ```

2. **Worker shape** (the demo's heart — keep it 20 lines):

   ```scala
   def start(id: String, totalSteps: Int): Unit =
     Source.tick(0.millis, 200.millis, ())
       .scan(0)((n, _) => n + 1)
       .takeWhile(_ <= totalSteps)
       .runForeach { step =>
         val frag =
           if step < totalSteps then
             TurboStream.stream(
               TurboStream.Action.Update,
               s"job-$id-progress",
               views.html._progress(step, totalSteps)
             )
           else
             TurboStream.stream(
               TurboStream.Action.Update,
               s"job-$id-progress",
               views.html._progress_done(id)
             )
         bus.publish(topicFor(id), frag)
       }
   ```

   `Source.tick` runs on the actor-system dispatcher; doesn't block any HTTP
   thread. Multi-job concurrency is free — each call materialises an
   independent stream.

3. **Templates** — `_progress.scala.html`, `_progress_done.scala.html`,
   `jobs.scala.html`, `job.scala.html`. ~80 lines of Twirl total.

4. **`src/main/scala/hotwire/Main.scala`** — wire `JobsRoutes` into the
   route concat (only when bus is mounted; the demo needs the bus). Add
   `Jobs` to the `Demo` enum:

   ```scala
   enum Demo: case All, Chat, Posts, Jobs
   ```

   `DEMO=jobs` mounts only `/jobs`; `DEMO=all` mounts everything.

5. **`src/test/scala/hotwire/JobsRoutesSpec.scala`**:
   - GET /jobs renders.
   - POST /jobs creates a job and redirects.
   - Subscribing to the bus topic *before* starting yields N+1 frames
     (N progress, 1 done). Use `Source.tick` with a tight interval (10ms,
     N=5) so the test runs in <100ms.

### Why this is the right showcase for the bus

- **Pub side is non-HTTP.** The worker is a `Source.tick` running on the
  ActorSystem dispatcher. No request-scoped context. This is exactly the
  shape (cron jobs, queue consumers, Pekko Cluster events) that the trait
  was designed to abstract over.
- **Sub side is what you'd expect.** A `<turbo-stream-source>` WS,
  identical to chat.
- **Pairs naturally with JetStream.** A 2-minute job + a 5-second WS hiccup
  + core NATS = stuck progress bar at "12/100". Same scenario on JetStream
  + 60s replay horizon = bar catches up to the most recent tick on
  reconnect. Concrete demonstration of what the third bus implementation
  buys you.

### Multi-node consideration

For chat, a 2-node demo is the showcase: post on node A, observe on node B.
For jobs, the worker is started on whichever node received the POST. If the
WS is on a *different* node, that node also subscribes to `job:42` and
receives published frames — same fan-out as chat. Works on both core NATS
and JetStream. Nothing extra to do.

What *doesn't* work: the worker is in-memory. If the node running the
worker dies, the job is lost. Out of scope — durable jobs need a job store
(Postgres, JetStream KV, etc.). Document in the demo's README section.

### Open questions

- **Cancel a running job.** Trivial extension: keep the materialised
  `KillSwitch` in `jobs` and add `DELETE /jobs/:id`. Worth a follow-up but
  not v1.
- **Auto-redirect on completion.** The `_progress_done.scala.html` could
  ship a `redirect` `<turbo-stream>` (from the `turbo_power` plan) instead
  of a "back" link. If both plans land, do the redirect variant in a
  follow-up that depends on both.
- **Backpressure when many subscribers join late.** `BroadcastHub` already
  isolates slow consumers; the bus impls use `dropHead`. Same story as
  chat — well-trodden.

---

## Rollout order

1. PR #1: `JobsRoutes` + templates + `Demo.Jobs` + tests, on the existing
   in-process bus. Smallest viable progress-bar demo, no JetStream
   dependency.
2. PR #2: `JetStreamBroadcastBus` + `BUS=jetstream` selector + manual-test
   README section. No demo wiring change — just a third backend.
3. PR #3: Document the "watch a job survive a reconnect" recipe (start
   broker with `-js`, run with `BUS=jetstream`, kill/restart browser tab,
   observe replayed frames). One paragraph in README.

PR #1 and PR #2 are independent; either can land first.
