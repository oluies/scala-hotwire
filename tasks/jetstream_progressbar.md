# Plan: JetStream durable consumer + background-job progress bar

Two changes that exercise the parts of the pub/sub façade the existing demos
don't: **non-HTTP-driven publishers** (a worker pushing progress frames into the
bus on its own clock) and **replay-on-reconnect** (a third `BroadcastBus`
implementation backed by JetStream).

References:
- NATS JetStream concepts: <https://docs.nats.io/nats-concepts/jetstream>
- jnats JetStream API (2.20.x): <https://github.com/nats-io/nats.java#jetstream>
- Existing TODO marker: `README.md` under "What's deliberately not here".

The two parts are independent. PR #1 (progress bar on in-process bus) ships
first; PR #2 (`JetStreamBroadcastBus`) is purely additive; PR #3 documents the
two together.

---

## Part 1 — Background-job progress bar (PR #1)

### Why first

The existing demos are HTTP-driven publishers (chat POST → publish; posts has
no bus at all). The bus trait is designed for *non-HTTP* publishers — cron
jobs, queue consumers, Pekko Streams pipelines. A progress bar is the canonical
demonstration: a `Source.tick` worker on the actor-system dispatcher publishes
one Turbo Stream frame per tick. Zero new client-side JS.

The progress bar works on every existing bus implementation; JetStream is what
makes *reconnect replay* work, but the demo runs fine without it.

### UX contract

```
GET  /jobs                  → list of jobs + "Start a job" form
POST /jobs                  → 303 redirect to /jobs/:id; worker started
GET  /jobs/:id              → progress page with <turbo-stream-source> + initial bar
WS   /streams/jobs/:id      → progress frames
```

Progress frame on each tick (one update per `target=#job-<id>-progress`):

```html
<turbo-stream action="update" target="job-42-progress"><template>
  <progress value="48" max="100">48%</progress>
  <span>48/100 widgets reticulated</span>
</template></turbo-stream>
```

On completion the same target is updated with a "Done" panel containing a
back link.

### Files to create / edit

| Path | Action |
| --- | --- |
| `src/main/scala/hotwire/JobsRoutes.scala` | new |
| `src/main/twirl/views/jobs.scala.html` | new |
| `src/main/twirl/views/job.scala.html` | new |
| `src/main/twirl/views/_progress.scala.html` | new |
| `src/main/twirl/views/_progress_done.scala.html` | new |
| `src/main/scala/hotwire/Main.scala` | edit (add `Demo.Jobs`, mount route) |
| `src/test/scala/hotwire/JobsRoutesSpec.scala` | new |

### `src/main/scala/hotwire/JobsRoutes.scala` (full file)

```scala
package hotwire

import hotwire.TwirlSupport.given
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}

import java.time.Instant
import java.util.UUID
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

/** Background-job progress demo. The publisher is a `Source.tick`
  * materialised on the ActorSystem dispatcher — i.e. a non-HTTP publisher,
  * the case the bus abstraction was designed for. One Turbo Stream frame
  * per tick is published on `topicFor(id)` and fanned out to every WS
  * subscribed to `/streams/jobs/<id>`.
  *
  * Per-job state is in-memory; if the node dies the job is lost. Durable
  * jobs need a job store and are out of scope. */
final class JobsRoutes(
    bus: BroadcastBus,
    tickInterval: FiniteDuration = 200.millis,
    defaultSteps: Int = 100
)(using system: ActorSystem[?]):
  import JobsRoutes.*

  private val jobs = TrieMap.empty[String, JobState]

  private def topicFor(id: String): String = s"job:$id"

  val routes: Route =
    concat(
      pathPrefix("jobs") {
        concat(
          pathEndOrSingleSlash {
            concat(
              get {
                CsrfSupport.withCsrfToken { csrf =>
                  complete(views.html.jobs(jobs.values.toVector.sortBy(_.startedAt).reverse, csrf))
                }
              },
              post {
                CsrfSupport.withCsrfToken { csrf =>
                  CsrfSupport.requireCsrf(csrf) {
                    val id = UUID.randomUUID().toString.take(8)
                    start(id, defaultSteps)
                    redirect(s"/jobs/$id", StatusCodes.SeeOther)
                  }
                }
              }
            )
          },
          path(Segment) { id =>
            get {
              CsrfSupport.withCsrfToken { csrf =>
                extractRequest { req =>
                  val scheme = if req.uri.scheme == "https" then "wss" else "ws"
                  val wsUrl  = s"$scheme://${req.uri.authority}/streams/jobs/$id"
                  val state  = jobs.getOrElse(id, JobState(id, 0, defaultSteps, Instant.now()))
                  complete(views.html.job(state, csrf, wsUrl))
                }
              }
            }
          }
        )
      },
      path("streams" / "jobs" / Segment) { id =>
        val outbound: Source[Message, NotUsed] =
          bus.subscribe(topicFor(id)).map(html => TextMessage(html))
        val flow = Flow.fromSinkAndSourceCoupled(Sink.ignore, outbound)
        handleWebSocketMessages(flow)
      }
    )

  /** Kick off the worker. Runs on the system dispatcher; multi-job is free. */
  private[hotwire] def start(id: String, totalSteps: Int): Unit =
    val state = JobState(id, 0, totalSteps, Instant.now())
    jobs.update(id, state)

    Source.tick(0.millis, tickInterval, ())
      .scan(0)((n, _) => n + 1)
      .takeWhile(_ <= totalSteps, inclusive = true)
      .runForeach { step =>
        jobs.update(id, state.copy(step = step))
        val frag =
          if step < totalSteps then
            TurboStream.stream(
              TurboStream.Action.Update,
              s"job-$id-progress",
              views.html._progress(id, step, totalSteps)
            )
          else
            TurboStream.stream(
              TurboStream.Action.Update,
              s"job-$id-progress",
              views.html._progress_done(id)
            )
        bus.publish(topicFor(id), frag)
      }(using system)
    ()

object JobsRoutes:
  final case class JobState(id: String, step: Int, total: Int, startedAt: Instant):
    def percent: Int =
      if total <= 0 then 100
      else math.min(100, math.round(step.toDouble / total.toDouble * 100).toInt)
    def done: Boolean = step >= total
```

Notes on differences vs the old sketch:
- `takeWhile(_ <= totalSteps, inclusive = true)` so the final tick (step ==
  total) is the one that publishes `_progress_done`. The old sketch used
  `.takeWhile(_ <= total)` which already includes total because of the `<=`,
  but `inclusive = true` is the canonical Pekko API for "emit the boundary
  element"; without it the test for the done frame is racy.
- `runForeach(...)(using system)` — Pekko 1.1.x's `runForeach` needs an
  implicit `Materializer`; the typed `ActorSystem` is one via the `Materializer`
  given in `pekko-stream`. Already in scope in `ChatRoutes` because of the
  `using system` constructor parameter.
- `JobState` lives on the companion to keep the routes file readable, mirrors
  `ChatRoutes.ChatMessage`.

### Twirl templates

`src/main/twirl/views/_progress.scala.html`:

```html
@(id: String, step: Int, total: Int)
<div id="job-@(id)-progress" class="job-progress">
  <progress value="@step" max="@total">@step/@total</progress>
  <span>@step / @total widgets reticulated</span>
</div>
```

`src/main/twirl/views/_progress_done.scala.html`:

```html
@(id: String)
<div id="job-@(id)-progress" class="job-progress done">
  <p>Job @id done. <a href="/jobs">Back to jobs</a>.</p>
</div>
```

`src/main/twirl/views/job.scala.html`:

```html
@(state: hotwire.JobsRoutes.JobState, csrfToken: String, wsUrl: String)
@views.html.layout("Job " + state.id, csrfToken) {
  <header>
    <h1>Job @state.id</h1>
    <p class="hint">Refresh, or open this page in a second tab — both will see the same ticks.</p>
  </header>

  <turbo-stream-source src="@wsUrl"></turbo-stream-source>

  @if(state.done) {
    @views.html._progress_done(state.id)
  } else {
    @views.html._progress(state.id, state.step, state.total)
  }

  <p><a href="/jobs">All jobs</a></p>
}
```

`src/main/twirl/views/jobs.scala.html`:

```html
@(items: Seq[hotwire.JobsRoutes.JobState], csrfToken: String)
@views.html.layout("Jobs", csrfToken) {
  <header>
    <h1>Jobs</h1>
    <p class="hint">Start a job and watch its progress bar tick. Each tick is one Turbo Stream frame on a WebSocket.</p>
  </header>

  <form action="/jobs" method="post" class="compose">
    <input type="hidden" name="_csrf" value="@csrfToken">
    <button type="submit">Start a job</button>
  </form>

  <ul class="jobs">
    @for(j <- items) {
      <li>
        <a href="/jobs/@j.id">Job @j.id</a> — @j.percent%
        @if(j.done) { <em>(done)</em> }
      </li>
    }
    @if(items.isEmpty) {
      <li class="hint">No jobs yet — start one above.</li>
    }
  </ul>
}
```

### `src/main/scala/hotwire/Main.scala` — diff

```diff
   enum Demo:
-    case All, Chat, Posts
+    case All, Chat, Posts, Jobs
   object Demo:
     def parse(s: String): Demo = s.trim.toLowerCase match
       case "all" | ""   => All
       case "chat"       => Chat
       case "posts"      => Posts
+      case "jobs"       => Jobs
       case other        =>
-        sys.error(s"Unknown DEMO=$other (expected one of: all, chat, posts)")
+        sys.error(s"Unknown DEMO=$other (expected one of: all, chat, posts, jobs)")
```

```diff
     val busOpt: Option[BroadcastBus] = demo match
-      case Demo.Posts => None
+      case Demo.Posts => None
       case _ =>
         natsUrl match
           ...
```

(`Demo.Jobs` falls through to the `case _` branch, so the bus is built. No
other change required to the bus selector.)

```diff
-    val chatRoutes:  Option[Route] = busOpt.map(b => new ChatRoutes(b).routes)
-    val postsRoutes: Option[Route] = Option.when(demo != Demo.Chat)(new PostsRoutes().routes)
+    val chatRoutes:  Option[Route] =
+      Option.when(demo == Demo.All || demo == Demo.Chat)(busOpt).flatten
+        .map(b => new ChatRoutes(b).routes)
+    val postsRoutes: Option[Route] =
+      Option.when(demo == Demo.All || demo == Demo.Posts)(new PostsRoutes().routes)
+    val jobsRoutes:  Option[Route] =
+      Option.when(demo == Demo.All || demo == Demo.Jobs)(busOpt).flatten
+        .map(b => new JobsRoutes(b).routes)

-    val landing = demo match
-      case Demo.Posts => "/posts"
-      case _          => "/chat/lobby"
+    val landing = demo match
+      case Demo.Posts => "/posts"
+      case Demo.Jobs  => "/jobs"
+      case _          => "/chat/lobby"

     val mounted: Seq[Route] =
-      chatRoutes.toSeq ++ postsRoutes.toSeq ++ Seq(
+      chatRoutes.toSeq ++ postsRoutes.toSeq ++ jobsRoutes.toSeq ++ Seq(
         pathPrefix("public") { getFromResourceDirectory("public") },
         pathSingleSlash { redirect(landing, StatusCodes.TemporaryRedirect) }
       )
```

Behaviour preserved: `DEMO=chat` no longer mounts posts (it didn't before
either — the `demo != Chat` guard handled that); `DEMO=jobs` mounts only
jobs and uses the bus; `DEMO=posts` still mounts no bus.

### Tests — `src/test/scala/hotwire/JobsRoutesSpec.scala`

Mirrors `ChatRoutesSpec` for HTTP, plus a fast worker test using a 5ms tick.

```scala
package hotwire

import munit.FunSuite
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.{Cookie, Location, `Set-Cookie`}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.Sink

import scala.concurrent.Await
import scala.concurrent.duration.*

class JobsRoutesSpec extends FunSuite:

  given system: ActorSystem[Nothing] =
    ActorSystem(Behaviors.empty, "JobsRoutesSpec")

  override def afterAll(): Unit = system.terminate()

  private def newRoutes(steps: Int = 5): (BroadcastBus, JobsRoutes) =
    val bus = new InProcessBroadcastBus()
    (bus, new JobsRoutes(bus, tickInterval = 5.millis, defaultSteps = steps))

  private def handler(r: JobsRoutes): HttpRequest => scala.concurrent.Future[HttpResponse] =
    Route.toFunction(r.routes)

  test("GET /jobs renders and sets a csrf cookie") {
    val (_, r) = newRoutes()
    val resp   = Await.result(handler(r)(HttpRequest(uri = "/jobs")), 5.seconds)
    assertEquals(resp.status, StatusCodes.OK)
    val cookie = resp.headers.collectFirst {
      case `Set-Cookie`(c) if c.name == "csrf_token" => c
    }
    assert(cookie.isDefined)
  }

  test("POST /jobs without CSRF is rejected") {
    val (_, r) = newRoutes()
    val resp   = Await.result(
      handler(r)(HttpRequest(HttpMethods.POST, "/jobs")),
      5.seconds
    )
    assertNotEquals(resp.status, StatusCodes.SeeOther)
  }

  test("POST /jobs with CSRF redirects to /jobs/:id") {
    val (_, r) = newRoutes()
    val token  = "test-token"
    val req    = HttpRequest(HttpMethods.POST, "/jobs")
      .addHeader(Cookie("csrf_token" -> token))
      .withEntity(FormData("_csrf" -> token).toEntity)
    val resp = Await.result(handler(r)(req), 5.seconds)
    assertEquals(resp.status, StatusCodes.SeeOther)
    val loc = resp.headers.collectFirst { case Location(uri) => uri.toString }
    assert(loc.exists(_.startsWith("/jobs/")), s"expected redirect to /jobs/<id>, got $loc")
  }

  test("worker publishes N progress frames + 1 done frame") {
    val (bus, r) = newRoutes(steps = 5)
    val id       = "test-id"
    // Subscribe BEFORE start so we capture every frame.
    val collected = bus.subscribe(s"job:$id")
      .take(6) // 5 progress + 1 done
      .runWith(Sink.seq)

    r.start(id, totalSteps = 5)

    val frames = Await.result(collected, 5.seconds)
    assertEquals(frames.size, 6)
    // Frames 1..4 are running progress; frame 5 (final) is _progress_done.
    assert(frames.take(5).forall(_.contains("""target="job-test-id-progress"""")))
    assert(frames(5).contains("Job test-id done"), frames(5))
    assert(!frames.head.contains("Back to jobs"))
  }
```

The `take(6)` guard avoids a leaking subscriber if the worker over-emits.
Same hot-subscribe race that `InProcessBroadcastBusSpec` solves with a
sentinel exists here in principle, but the worker's first tick fires at
`0.millis` and the `runWith(Sink.seq)` materialises before `start` is
called, so the gap is tiny in practice; if CI flakes, port the
`hotSubscribe` helper from `InProcessBroadcastBusSpec`.

### Validation steps

1. `sbt compile` — expect clean compile of new files; no `-Wunused` warnings.
2. `sbt test` — expect new `JobsRoutesSpec` to pass with 4 tests, no
   regression in `ChatRoutesSpec` / `PostsRoutesSpec` / `InProcessBroadcastBusSpec` /
   `TurboStreamSpec`.
3. `DEMO=jobs sbt run`, then in two terminals:
   - `curl -i http://localhost:8080/jobs` — expect `200 OK`,
     `Set-Cookie: csrf_token=…`, body contains `<form action="/jobs"`.
   - `curl -i -b cookies.txt -c cookies.txt http://localhost:8080/jobs` then
     `csrf=$(grep csrf_token cookies.txt | awk '{print $7}')` then
     `curl -i -b cookies.txt -X POST -d "_csrf=$csrf" http://localhost:8080/jobs`
     — expect `303 See Other` with `Location: /jobs/<8-char-id>`.
4. Browser: open `http://localhost:8080/jobs`, click "Start a job", land on
   `/jobs/<id>`, watch the `<progress>` bar tick from 0 to 100 over ~20s,
   final state replaced with "Job <id> done".
5. Open `/jobs/<id>` in a second tab while job is running — both tabs tick
   in lockstep (proves WS fan-out via the bus).

### Done-when

- [ ] All 4 new tests pass; full `sbt test` green.
- [ ] `DEMO=jobs sbt run` lands on `/jobs`; `DEMO=all sbt run` mounts chat,
      posts, and jobs simultaneously.
- [ ] Browser test 4 above completes without console errors.
- [ ] Two-tab fan-out (test 5) shows synchronised ticks.
- [ ] README "What's in here" table updated to list the third demo (separate
      doc PR is fine).

### Rollback plan

PR #1 is fully additive at the source level except for the `Main.scala`
diff. Rollback = `git revert <pr1-commit>`. The `Main.scala` change adds
`Demo.Jobs` and an extra route option; reverting it restores the old
two-demo selector verbatim. No DB migrations, no new dependencies, no
config schema changes. `application.conf` is untouched.

---

## Part 2 — `JetStreamBroadcastBus` (PR #2)

### Why

`NatsBroadcastBus` uses core NATS pub/sub: at-most-once, no server-side
buffering. A WS that disconnects and reconnects 200ms later loses every
frame published in the gap. For chat that's tolerable. For a job progress
bar (PR #1) it's the difference between "bar resumes" and "bar stuck at
47%". JetStream adds a per-stream persistent log; a *durable consumer* with
a bounded start-time replays the last N seconds on resubscribe.

### Design decisions (settled)

1. **Per-subscribe durable consumer (one per topic, not per WS).** The bus
   creates a single durable per `subscribe(topic)` call, then fans its
   delivery out via a `BroadcastHub` to N HTTP/WS subscribers — exactly
   mirroring `NatsBroadcastBus.buildHub`. One tab dying doesn't tear the
   consumer down; only the last subscriber leaving does.
2. **Replay horizon = 60s by default**, configurable on the constructor.
   Use `DeliverPolicy.ByStartTime(now - replayHorizon)`. Avoids
   `DeliverPolicy.All` (would replay all chat history on every reload).
3. **Inactive consumer cleanup = 5 minutes via `inactiveThreshold`.** If
   the JVM crashes between "subscriber gone" and our `deleteConsumer`
   call, JetStream itself reaps the consumer after 5 min idle. 5 min is
   chosen as ~50× the WS reconnect window (which we already cap at
   `pekko.http.server.idle-timeout = 120s`); short enough that crash-loop
   churn doesn't accumulate consumers, long enough that a genuinely-slow
   reconnect inside the same materialised hub doesn't get reaped
   prematurely.
4. **Subject mapping = identical to `NatsBroadcastBus`.** No
   `hotwire.` prefix. The `subjects` filter on the stream is `>` (catch-all)
   so subjects already in use by `NatsBroadcastBus` (`chat.lobby`,
   `job.<id>`) flow into the same stream when both implementations talk to
   the same broker. Resolves the prefix-collision question: a node running
   the JetStream impl and a node running the core impl against the same
   broker *do* see each other's traffic, because the JetStream stream
   captures the same subjects the core impl already publishes on. (Stream
   capture is invisible to core publishers — they keep using
   `connection.publish`.)
5. **Storage = File**, configurable. Memory loses replay across broker
   restarts; File on a local NATS dev box costs nothing. Demo expectations
   align with "60s replay horizon survives a broker bounce".
6. **`ensureStream` error handling — narrow.** Catch only the specific
   "stream already exists" condition by checking `ApiErrorCode == 10058`.
   The old sketch's blanket `case _: JetStreamApiException` would swallow
   auth errors, unreachable broker, JetStream-not-enabled — replaced with
   a precise check + rethrow on anything else.

### Files to create / edit

| Path | Action |
| --- | --- |
| `src/main/scala/hotwire/JetStreamBroadcastBus.scala` | new |
| `src/main/scala/hotwire/Main.scala` | edit (third bus branch) |
| `src/main/resources/application.conf` | edit (add `bus` key) |

`build.sbt`: jnats 2.20.5 already includes JetStream client classes
(`io.nats.client.JetStream`, `io.nats.client.api.*`). No new dependency.

### `src/main/scala/hotwire/JetStreamBroadcastBus.scala` (full file)

```scala
package hotwire

import io.nats.client.api.{
  ConsumerConfiguration, DeliverPolicy, RetentionPolicy,
  StorageType, StreamConfiguration
}
import io.nats.client.{
  Connection, JetStream, JetStreamApiException, JetStreamSubscription,
  PushSubscribeOptions
}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.{BroadcastHub, Keep, Sink, Source, SourceQueueWithComplete}

import java.nio.charset.StandardCharsets
import java.time.{Duration, ZonedDateTime}
import java.util.concurrent.ConcurrentHashMap
import java.util.function.{Function => JFunction}
import scala.concurrent.Future

/** JetStream-backed bus with bounded reconnect-replay window.
  *
  * On startup ensures a stream named `streamName` capturing every subject
  * matching `subjectFilter` exists. Each `subscribe(topic)` creates a
  * uniquely-named durable consumer with `DeliverPolicy.ByStartTime(now -
  * replayHorizon)`, fanned out to N application subscribers via a
  * BroadcastHub (mirrors `NatsBroadcastBus` topology).
  *
  * Subject mapping matches `NatsBroadcastBus`: ':' and '/' → '.'. A core-NATS
  * publisher on the same broker will land in this stream provided
  * `subjectFilter` includes its subject (default '>' = catch-all).
  */
final class JetStreamBroadcastBus(
    connection: Connection,
    streamName: String = "hotwire",
    subjectFilter: String = ">",
    perTopicBufferSize: Int = 64,
    replayHorizon: Duration = Duration.ofSeconds(60),
    inactiveThreshold: Duration = Duration.ofMinutes(5),
    storageType: StorageType = StorageType.File
)(using system: ActorSystem[?])
    extends BroadcastBus:

  private given ec: scala.concurrent.ExecutionContext = system.executionContext

  private val js: JetStream = connection.jetStream()

  private final case class Hub(
      subscription: JetStreamSubscription,
      durableName: String,
      queue: SourceQueueWithComplete[String],
      source: Source[String, NotUsed]
  )

  private val hubs = new ConcurrentHashMap[String, Hub]()

  ensureStream()

  private def topicToSubject(topic: String): String =
    topic.replace(':', '.').replace('/', '.')

  private def buildHub(topic: String): Hub =
    val (queue, raw) =
      Source.queue[String](perTopicBufferSize, OverflowStrategy.dropHead)
        .toMat(BroadcastHub.sink[String](bufferSize = perTopicBufferSize))(Keep.both)
        .run()
    raw.runWith(Sink.ignore) // anchor

    val subject  = topicToSubject(topic)
    val durable  = s"hotwire-${subject.replace('.', '-')}-${java.util.UUID.randomUUID().toString.take(8)}"
    val consumerCfg = ConsumerConfiguration.builder()
      .durable(durable)
      .deliverPolicy(DeliverPolicy.ByStartTime)
      .startTime(ZonedDateTime.now().minus(replayHorizon))
      .inactiveThreshold(inactiveThreshold)
      .build()

    val opts = PushSubscribeOptions.builder()
      .stream(streamName)
      .configuration(consumerCfg)
      .build()

    val dispatcher = connection.createDispatcher()
    val sub = js.subscribe(
      subject,
      dispatcher,
      msg => {
        val payload = new String(msg.getData, StandardCharsets.UTF_8)
        queue.offer(payload)
        msg.ack()
        ()
      },
      /* autoAck = */ false,
      opts
    )

    Hub(sub, durable, queue, raw)

  private val builder: JFunction[String, Hub] =
    (t: String) => buildHub(t)

  private def hubFor(topic: String): Hub =
    hubs.computeIfAbsent(topic, builder)

  override def publish(topic: String, html: String): Unit =
    // Core-publish is fine: the stream's subject filter captures it.
    // Avoids the JetStream publish ack round-trip on the hot path.
    connection.publish(topicToSubject(topic), html.getBytes(StandardCharsets.UTF_8))

  override def subscribe(topic: String): Source[String, NotUsed] =
    hubFor(topic).source

  override def shutdown(): Future[Unit] = Future {
    hubs.values().forEach { h =>
      try h.subscription.unsubscribe() catch case _: Throwable => ()
      try connection.jetStreamManagement().deleteConsumer(streamName, h.durableName)
      catch case _: Throwable => () // best-effort; inactiveThreshold reaps anyway
      h.queue.complete()
    }
    try connection.flush(Duration.ofSeconds(2))
    catch case _: Throwable => ()
    connection.close()
  }

  /** Idempotent stream creation. Catches *only* the "stream name already in
    * use" API error (10058 in nats-server >= 2.10), reconciles by calling
    * `updateStream`. Other JetStreamApiExceptions (auth, no JetStream
    * enabled, etc.) propagate. */
  private def ensureStream(): Unit =
    val mgr = connection.jetStreamManagement()
    val cfg = StreamConfiguration.builder()
      .name(streamName)
      .subjects(subjectFilter)
      .retentionPolicy(RetentionPolicy.Limits)
      .storageType(storageType)
      .maxAge(replayHorizon.multipliedBy(10)) // headroom for late reconnects
      .build()
    try
      mgr.addStream(cfg)
      ()
    catch
      case e: JetStreamApiException if e.getApiErrorCode == 10058 =>
        // Stream already exists — reconcile config in case subjects/maxAge changed.
        mgr.updateStream(cfg)
        ()
      // Any other JetStreamApiException (e.g. JS not enabled, unauthorised)
      // propagates out of the constructor and aborts startup.
```

Notes:
- API error code `10058` is the documented "stream name already in use" code
  in nats-server. If the broker reports a different code (older versions),
  the constructor will fail with a clear `JetStreamApiException` instead of
  silently swallowing it — fail-fast beats lying.
- `connection.publish` rather than `js.publish` on the hot path: skips the
  ack round-trip. Core publishes are still captured by JetStream because
  the stream subscribes to the subject. Documented in the class doc.
- `inactiveThreshold(Duration.ofMinutes(5))` requires jnats ≥ 2.16; 2.20.5
  is fine.

### `Main.scala` selector — diff

```diff
-    val busOpt: Option[BroadcastBus] = demo match
-      case Demo.Posts => None
-      case _ =>
-        natsUrl match
-          case Some(url) =>
-            log.info(s"Connecting to NATS at $url")
-            Some(new NatsBroadcastBus(NatsBroadcastBus.connect(url)))
-          case None =>
-            log.info("Using in-process broadcast bus (set NATS_URL to use NATS)")
-            Some(new InProcessBroadcastBus())
+    val bus = Try(cfg.getString("bus")).toOption.map(_.trim.toLowerCase).filter(_.nonEmpty)
+    val busOpt: Option[BroadcastBus] = demo match
+      case Demo.Posts => None
+      case _ =>
+        natsUrl match
+          case Some(url) if bus.contains("jetstream") =>
+            log.info(s"Connecting to NATS JetStream at $url")
+            Some(new JetStreamBroadcastBus(NatsBroadcastBus.connect(url)))
+          case Some(url) =>
+            log.info(s"Connecting to NATS (core) at $url")
+            Some(new NatsBroadcastBus(NatsBroadcastBus.connect(url)))
+          case None =>
+            log.info("Using in-process broadcast bus (set NATS_URL to use NATS)")
+            Some(new InProcessBroadcastBus())
```

### `application.conf` — diff

```diff
   # Leave unset to use the in-process bus. Set to e.g. "nats://localhost:4222" to use NATS.
   nats-url = ${?NATS_URL}

+  # When NATS_URL is set, picks the NATS bus implementation. One of:
+  #   core       — at-most-once core NATS pub/sub (default).
+  #   jetstream  — JetStream durable consumer with 60s replay-on-reconnect.
+  bus = ${?BUS}
+
   # Which demo(s) to mount. One of: all, chat, posts. Default: all.
```

### Tests — committed approach: manual verification only

JetStream needs a real broker. We do **not** add it to CI. We do not add
testcontainers either: the only thing it would buy us is automated
verification that "stream is created idempotently and one consumer
delivers replay" — which is JetStream's own contract, exhaustively tested
upstream. The cost (a Docker dependency in CI, ~5s startup per test class,
flake surface area) outweighs the benefit for a thin adapter.

Manual recipe (also goes into README in PR #3):

1. Start a JetStream-enabled broker:
   ```bash
   nats-server -js --store_dir /tmp/jshotwire
   ```
2. Run the app:
   ```bash
   NATS_URL=nats://localhost:4222 BUS=jetstream DEMO=all sbt run
   ```
3. **Stream-creation check.** In another terminal:
   ```bash
   nats stream ls   # nats CLI; brew install nats-io/nats-tools/nats
   ```
   Expect a stream named `hotwire`, subjects `>`, storage `File`.
4. **Replay check (chat).** Open `/chat/lobby`, send a message, close the
   tab. Wait 5s. Reopen. Expect the message redelivered as a Turbo Stream
   frame within 1s (within the 60s replay horizon).
5. **Replay check (jobs).** Start a job at `/jobs`. Close the tab at ~30%.
   Wait 10s. Reopen the job page. Expect the bar to jump to whatever the
   current step is and continue ticking — no stuck 30% bar.
6. **Cleanup check.** Close all tabs. Wait 6 minutes. `nats consumer ls hotwire`
   should show zero `hotwire-…` consumers (the `inactiveThreshold` reaped
   them; if the JVM exited cleanly they're already gone).

### Validation steps

1. `sbt compile` — clean compile; the new file pulls in JetStream API only,
   no new dependencies needed.
2. `sbt test` — no regressions; no new tests added in this PR.
3. `sbt run` (no env vars) — still uses in-process bus; chat + posts +
   jobs all work as in PR #1.
4. `NATS_URL=nats://localhost:4222 sbt run` — uses *core* bus (default
   `BUS` unset); behaviour identical to today.
5. `NATS_URL=nats://localhost:4222 BUS=jetstream sbt run` — uses
   JetStream bus; perform the manual replay recipe above.
6. `NATS_URL=nats://localhost:4222 BUS=jetstream sbt run` against a broker
   *without* `-js` — expect a fast crash on startup with a
   `JetStreamApiException` from `addStream`. Confirms `ensureStream`
   doesn't swallow real errors.

### Done-when

- [ ] `JetStreamBroadcastBus.scala` compiles, no `-Wunused` warnings.
- [ ] Default boot (no `NATS_URL`) and `BUS=core` boot are byte-identical
      in observable behaviour to before PR #2.
- [ ] Manual replay recipe steps 4 and 5 succeed.
- [ ] Step 6 (broker without `-js`) fails fast with a clear log line.
- [ ] `inactiveThreshold` value (5m) is documented in the class scaladoc
      *and* in `application.conf` comment.

### Rollback plan

- Code: pure addition. `git revert <pr2-commit>` removes
  `JetStreamBroadcastBus.scala`, the `bus =` line in `application.conf`,
  and reverts the `Main.scala` selector. Existing core-NATS and in-process
  paths are unchanged by PR #2's diff (only an additional branch was
  added), so nothing is at risk.
- Operational: if a deployment runs `BUS=jetstream` and we revert, drop
  the env var or set `BUS=core`. The on-broker stream named `hotwire`
  is harmless to leave behind; delete with `nats stream rm hotwire` if
  desired.

---

## Part 3 — README replay recipe (PR #3)

### Scope

Documentation only. No code, no config.

### Files to edit

| Path | Action |
| --- | --- |
| `README.md` | edit |

### `README.md` — diff

In the "What's deliberately not here" list, drop the JetStream bullet
(it's now implemented):

```diff
-* **JetStream durable consumers.** Add a `JetStreamBroadcastBus` implementation when
-  you need replay-on-reconnect. The trait stays the same.
```

In the "When to use which" table, mark JetStream as available:

```diff
-| Need WS-reconnect replay of missed frames      | NATS + JetStream¹     |
-
-¹ Out of scope for this demo — see `NatsBroadcastBus.scala` for where you'd swap the
-core dispatcher for a `JetStreamSubscription` with a durable consumer.
+| Need WS-reconnect replay of missed frames      | NATS + JetStream      |
```

After the "Switching to NATS" section, add:

````markdown
### Switching to NATS JetStream (replay on reconnect)

Core NATS is at-most-once: a WS that drops and reconnects 200ms later loses
every Turbo Stream frame published in the gap. JetStream adds a per-stream
log on the broker; `JetStreamBroadcastBus` creates a durable consumer per
topic with a 60-second replay horizon, so a reconnecting tab catches up.

Run a JetStream-enabled broker:

```bash
nats-server -js --store_dir /tmp/jshotwire
```

Run the app:

```bash
NATS_URL=nats://localhost:4222 BUS=jetstream sbt run
```

To verify replay end-to-end against the jobs demo:

1. Start a job at <http://localhost:8080/jobs>.
2. Close the tab when the bar reaches ~30%.
3. Wait 10 seconds.
4. Reopen the job page. Expect the bar to jump to its current step and
   keep ticking — not freeze at 30%.

Configurable knobs (constructor params on `JetStreamBroadcastBus`):

| Param | Default | Notes |
| --- | --- | --- |
| `streamName` | `"hotwire"` | shared with core-NATS publishers on the same broker (catch-all subject filter) |
| `subjectFilter` | `">"` | broaden/narrow what the stream captures |
| `replayHorizon` | `60s` | how far back a reconnecting subscriber sees |
| `inactiveThreshold` | `5m` | server-side reaper for orphaned consumers |
| `storageType` | `File` | switch to `Memory` for ephemeral demos |
````

### Validation steps

1. `markdownlint README.md` (or visual inspection) — no broken anchors,
   tables render.
2. Manually walk the new "Switching to NATS JetStream" section on a clean
   machine — every command runs as written.

### Done-when

- [ ] README's "What's deliberately not here" no longer claims JetStream is missing.
- [ ] New "Switching to NATS JetStream" section between "Switching to NATS"
      and "Tests".
- [ ] Replay recipe works copy-paste against a fresh `nats-server -js`.

### Rollback plan

`git revert <pr3-commit>`. README-only; zero blast radius.

---

## Rollout order

1. **PR #1 — Background-job progress bar on existing buses.** Smallest
   viable demo; no NATS dependency; fully testable in CI. Ships value
   immediately.
2. **PR #2 — `JetStreamBroadcastBus`.** Pure addition; selectable via
   `BUS=jetstream`. Default behaviour unchanged. Manual verification only.
3. **PR #3 — README replay recipe.** Documentation that ties #1 and #2
   together with a reproducible "watch a job survive a reconnect" walkthrough.

PR #1 and PR #2 are independent and can land in either order; PR #3 lands
after both.
