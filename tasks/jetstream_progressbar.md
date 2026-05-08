# Plan: Background-job progress bar (with optional JetStream replay)

> **Status note (2026-05-08).** This plan was originally drafted before
> [PR #4 (`e0ed93b`)](https://github.com/oluies/scala-hotwire/pull/4) landed.
> That PR shipped a JetStream-backed replay example under
> `hotwire.examples.jetstream`, with a different design than the original
> plan proposed (`ReplayableBroadcastBus` trait, per-tab seq tracking via
> `?last_seq=N`, `DeliverPolicy.ByStartSequence`, browser as source of
> truth — see `src/main/scala/hotwire/examples/jetstream/`).
>
> The plan below has been rewritten to match what shipped. The
> "build a `JetStreamBroadcastBus`" PR has been removed (already done).
> The progress-bar demo is split into:
> - **PR #1** — progress bar on the existing `BroadcastBus` (in-process /
>   core NATS). Standalone; no JetStream required.
> - **PR #2** — replay-aware progress bar on `ReplayableBroadcastBus`,
>   mirroring `ReplayChatRoutes`. Reuses the existing
>   `<replaying-turbo-stream-source>` element and `SeqStamping` helper.
> - **PR #3** — README documents both demos.

The whole point of this work is to exercise the parts of the bus the existing
demos don't: **non-HTTP-driven publishers** (a worker on the actor-system
dispatcher pushing progress frames on its own clock) and, optionally,
**reconnect replay** for a stream of frames the user can't easily reproduce
(unlike chat, you can't just retype what you missed).

References:
- Existing replay example (read these first — this plan extends them):
  - `src/main/scala/hotwire/examples/jetstream/ReplayableBroadcastBus.scala`
  - `src/main/scala/hotwire/examples/jetstream/JetStreamBroadcastBus.scala`
  - `src/main/scala/hotwire/examples/jetstream/ReplayChatRoutes.scala`
  - `src/main/resources/public/jetstream/replay.js`
  - `src/main/twirl/views/jetstream/chat.scala.html`
- NATS JetStream concepts: <https://docs.nats.io/nats-concepts/jetstream>

PR #1 and PR #2 are independent; PR #1 ships value with zero new
infrastructure, PR #2 layers on top of what PR #4 already wired up.

---

## Part 1 — Background-job progress bar on `BroadcastBus` (PR #1)

### Why first

The existing demos are HTTP-driven publishers (chat POST → publish; posts has
no bus at all; replay-chat POST → publishAndAck). The bus trait is designed
for *non-HTTP* publishers — cron jobs, queue consumers, Pekko Streams
pipelines. A progress bar is the canonical demonstration: a `Source.tick`
worker on the actor-system dispatcher publishes one Turbo Stream frame per
tick. Zero new client-side JS.

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

`Demo.Jobs` falls through the existing `case _ =>` branch in the bus
selector, so it gets the same in-process / NatsBroadcastBus depending on
`NATS_URL` — no change needed to that block.

```diff
-    val chatRoutes:   Option[Route] = busOpt.map(b => new ChatRoutes(b).routes)
-    val replayRoutes: Option[Route] = replayBus.map(b => new ReplayChatRoutes(b).routes)
-    val postsRoutes:  Option[Route] = Option.when(demo != Demo.Chat)(new PostsRoutes().routes)
+    val chatRoutes:   Option[Route] =
+      Option.when(demo == Demo.All || demo == Demo.Chat)(busOpt).flatten
+        .map(b => new ChatRoutes(b).routes)
+    val replayRoutes: Option[Route] = replayBus.map(b => new ReplayChatRoutes(b).routes)
+    val postsRoutes:  Option[Route] =
+      Option.when(demo == Demo.All || demo == Demo.Posts)(new PostsRoutes().routes)
+    val jobsRoutes:   Option[Route] =
+      Option.when(demo == Demo.All || demo == Demo.Jobs)(busOpt).flatten
+        .map(b => new JobsRoutes(b).routes)

     val landing = demo match
       case Demo.Posts => "/posts"
+      case Demo.Jobs  => "/jobs"
       case _          => "/chat/lobby"

     val mounted: Seq[Route] =
-      chatRoutes.toSeq ++ replayRoutes.toSeq ++ postsRoutes.toSeq ++ Seq(
+      chatRoutes.toSeq ++ replayRoutes.toSeq ++ postsRoutes.toSeq ++ jobsRoutes.toSeq ++ Seq(
```

Behaviour preserved: `DEMO=chat` mounts only chat (and replayChat when
`NATS_JS_STREAM` is set); `DEMO=jobs` mounts only jobs and uses the bus;
`DEMO=posts` still mounts no bus.

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
    val resp = Await.result(handler(req), 5.seconds)
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
    assert(frames.take(5).forall(_.contains("""target="job-test-id-progress"""")))
    assert(frames(5).contains("Job test-id done"), frames(5))
    assert(!frames.head.contains("Back to jobs"))
  }
```

### Validation steps

1. `sbt compile` — clean compile of new files; no `-Wunused` warnings.
2. `sbt test` — new `JobsRoutesSpec` passes (4 tests); no regression in
   `ChatRoutesSpec`, `PostsRoutesSpec`, `InProcessBroadcastBusSpec`,
   `TurboStreamSpec`, `SeqStampingSpec`.
3. `DEMO=jobs sbt run`, then:
   - `curl -i http://localhost:8080/jobs` — `200 OK`,
     `Set-Cookie: csrf_token=…`, body contains `<form action="/jobs"`.
   - `curl -c c.txt -b c.txt -i http://localhost:8080/jobs` then
     `csrf=$(awk '/csrf_token/ {print $7}' c.txt)` then
     `curl -b c.txt -i -X POST -d "_csrf=$csrf" http://localhost:8080/jobs`
     — `303 See Other`, `Location: /jobs/<8-char-id>`.
4. Browser: open `http://localhost:8080/jobs`, click "Start a job", land on
   `/jobs/<id>`, watch the `<progress>` bar tick from 0 to 100 over ~20s,
   final state replaced with "Job <id> done".
5. Open `/jobs/<id>` in a second tab while the job is running — both tabs
   tick in lockstep (proves WS fan-out via the bus).

### Done-when

- [ ] All 4 new tests pass; full `sbt test` green.
- [ ] `DEMO=jobs sbt run` lands on `/jobs`; `DEMO=all sbt run` mounts chat,
      posts, jobs (and replay-chat when `NATS_JS_STREAM` is set).
- [ ] Browser test 4 above completes without console errors.
- [ ] Two-tab fan-out (test 5) shows synchronised ticks.
- [ ] README "What's in here" table updated to list the new demo (separate
      doc PR is fine — see PR #3).

### Rollback plan

PR #1 is fully additive at the source level except for the `Main.scala`
diff. Rollback = `git revert <pr1-commit>`. The `Main.scala` change only
adds a `Demo.Jobs` case and an extra route option; reverting it restores
the existing three-demo selector verbatim. No DB migrations, no new
dependencies, no config schema changes. `application.conf` is untouched.

---

## Part 2 — Replay-aware progress bar on `ReplayableBroadcastBus` (PR #2)

### Why

Tab closes / network blips / lid sleep all kill the WS. On a chat that's
tolerable. On a job whose only output is a stream of progress ticks, the
user has no way to ask "what did I miss?" — the bar just freezes until the
next tick. Mounting the same demo on the existing `ReplayableBroadcastBus`
fixes that with no client-side state beyond what
`<replaying-turbo-stream-source>` already tracks (`sessionStorage` per
`data-room`).

This PR mirrors `ReplayChatRoutes` line-for-line, with three small twists
that the chat case glosses over because it uses a single shared subject
prefix:

1. **Subject namespace.** Chat uses `jschat.>`. Jobs need their own
   namespace (`jsjob.>`) so the catch-all consumer for chat doesn't ingest
   job frames. This requires generalising
   `JetStreamBroadcastBus.connectAndEnsureStream` to accept multiple
   subject wildcards.
2. **Replay key.** The `<replaying-turbo-stream-source>` element keys
   `sessionStorage` by `data-room`. For jobs we pass the job id as
   `data-room` — same JS, no change. The storage key is
   `lastSeq:<id>`. Documented inline in the new template.
3. **Stream limits.** The current default
   (`maxMessagesPerSubject=1000, maxAge=24h`) is sized for chat. A 100-
   step job at 200ms tick is 100 frames; jobs are much sparser per
   subject, so the existing limits work fine. Keep defaults.

### Decisions baked in

- **Reuse the existing `CHAT_REPLAY` stream** by extending its subjects to
  cover both `jschat.>` and `jsjob.>`. One stream, two subject namespaces.
  Cheaper than running two streams, and the broker wildcard model handles
  this directly.
- **Reuse `replay.js` unchanged.** The element only knows about
  `data-src` and `data-room`; we hand it a job id as `data-room`.
- **Reuse `SeqStamping`.** It's package-private to
  `hotwire.examples.jetstream` (object on `ReplayChatRoutes`'s companion).
  Either move it to a top-level object or import it from the new route
  file. We move it — see the diff.
- **Mount under `/jetstream-jobs/<id>`** to mirror `/jetstream-chat/<room>`.
  WS is `/jetstream-streams/jobs/<id>?last_seq=N`. Restrict `<id>` to the
  same `[a-zA-Z0-9_-]+` regex used by `ReplayChatRoutes` for the
  `*`/`>` wildcard hardening.

### Files to create / edit

| Path | Action |
| --- | --- |
| `src/main/scala/hotwire/examples/jetstream/SeqStamping.scala` | new (extracted from `ReplayChatRoutes`) |
| `src/main/scala/hotwire/examples/jetstream/ReplayChatRoutes.scala` | edit (drop the inline `SeqStamping` object, import from new file) |
| `src/main/scala/hotwire/examples/jetstream/JetStreamBroadcastBus.scala` | edit (`connectAndEnsureStream` takes `subjectsWildcards: Seq[String]`) |
| `src/main/scala/hotwire/examples/jetstream/ReplayJobsRoutes.scala` | new |
| `src/main/twirl/views/jetstream/jobs.scala.html` | new |
| `src/main/twirl/views/jetstream/job.scala.html` | new |
| `src/main/scala/hotwire/Main.scala` | edit (mount `ReplayJobsRoutes`; pass both wildcards) |
| `src/test/scala/hotwire/examples/jetstream/SeqStampingSpec.scala` | edit (update import path; tests unchanged) |

### `SeqStamping` extraction

**`src/main/scala/hotwire/examples/jetstream/SeqStamping.scala`** (new) —
verbatim move of the existing `ReplayChatRoutes.SeqStamping` object body
into its own top-level object so `ReplayJobsRoutes` can use it without an
awkward import path:

```scala
package hotwire.examples.jetstream

/** Inserts a `data-seq="N"` attribute on the *first* `<turbo-stream` element of an
  * already-rendered fragment. The fragment is generated by
  * [[hotwire.TurboStream]] so the structure is predictable: it always starts
  * with the literal `<turbo-stream ` (note the trailing space).
  *
  * Idempotent: if a `data-seq="…"` is already present in the *opening tag's
  * attribute list* it is left untouched. We deliberately do NOT scan the whole
  * fragment for `data-seq=` because user-controlled content inside
  * `<template>…</template>` could legitimately include the literal substring
  * (Twirl HTML-escapes `<`, `>`, `&`, and quotes — but not `=` or word chars). */
object SeqStamping:
  private val needle = "<turbo-stream "

  def stamp(fragment: String, seq: Long): String =
    val idx = fragment.indexOf(needle)
    if idx < 0 then fragment
    else
      val tagEnd = fragment.indexOf('>', idx + needle.length)
      val alreadyStamped =
        tagEnd >= 0 && fragment.substring(idx, tagEnd).contains("data-seq=")
      if alreadyStamped then fragment
      else
        val insertAt = idx + needle.length
        val (before, after) = fragment.splitAt(insertAt)
        s"""${before}data-seq="$seq" $after"""
```

**`src/main/scala/hotwire/examples/jetstream/ReplayChatRoutes.scala`** —
delete the inline `object SeqStamping` and update the import:

```diff
-import hotwire.examples.jetstream.ReplayChatRoutes.SeqStamping
+import hotwire.examples.jetstream.SeqStamping
```

```diff
-object ReplayChatRoutes:
-
-  /** Inserts a `data-seq="N"` attribute … */
-  object SeqStamping:
-    private val needle = "<turbo-stream "
-    def stamp(fragment: String, seq: Long): String = …
```

**`src/test/scala/hotwire/examples/jetstream/SeqStampingSpec.scala`** —
update the import. All 5 existing tests stay; no new tests.

```diff
-import hotwire.examples.jetstream.ReplayChatRoutes.SeqStamping
+import hotwire.examples.jetstream.SeqStamping
```

### `JetStreamBroadcastBus.connectAndEnsureStream` — multi-subject

**`src/main/scala/hotwire/examples/jetstream/JetStreamBroadcastBus.scala`**
— extend the helper to accept multiple subject wildcards. Keep the
single-string overload as a thin wrapper for source-compat:

```diff
-  def connectAndEnsureStream(
-      url: String = Options.DEFAULT_URL,
-      streamName: String = "CHAT_REPLAY",
-      subjectsWildcard: String = "jschat.>",
+  def connectAndEnsureStream(
+      url: String = Options.DEFAULT_URL,
+      streamName: String = "CHAT_REPLAY",
+      subjectsWildcards: Seq[String] = Seq("jschat.>"),
       maxMsgsPerSubject: Long = 1000L,
       maxAge: Duration = Duration.ofHours(24)
   ): Connection =
     val connection = Nats.connect(url)
     val jsm        = connection.jetStreamManagement()
     val cfg = StreamConfiguration.builder()
       .name(streamName)
-      .subjects(subjectsWildcard)
+      .subjects(subjectsWildcards*)
       .retentionPolicy(RetentionPolicy.Limits)
       .maxMessagesPerSubject(maxMsgsPerSubject)
       .maxAge(maxAge)
       .storageType(StorageType.File)
       .build()
```

(Single-subject callers can pass `Seq("foo.>")`. If any existing call site
becomes awkward, add a `def connectAndEnsureStream(url, streamName,
subjectsWildcard: String, …)` overload — but the only call site is
`Main.scala`, which we update below.)

**`StreamConfiguration.subjects(...)`** in jnats 2.20.5 takes varargs of
`String` *or* a `Collection[String]`. Verify with:
`javap -c io.nats.client.api.StreamConfiguration$Builder | grep subjects`.
If only varargs: `.subjects(subjectsWildcards*)` is correct.

### `ReplayJobsRoutes.scala` (full file)

```scala
package hotwire.examples.jetstream

import hotwire.{CsrfSupport, TurboStream, TwirlSupport}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}

import java.util.UUID
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

/** Replay-aware variant of [[hotwire.JobsRoutes]], built on
  * [[ReplayableBroadcastBus]]. Mirrors the structure of [[ReplayChatRoutes]]:
  *
  *   1. Every `<turbo-stream>` fragment carries a `data-seq` attribute
  *      stamped from the JetStream sequence — see [[SeqStamping]].
  *
  *   2. The page renders empty; the WebSocket replay (driven by the
  *      `<replaying-turbo-stream-source>` element) is the single source of
  *      truth for "what has this tab seen". A reconnecting tab passes
  *      `?last_seq=N` and backfills only what was missed; a fresh tab
  *      passes `?last_seq=0` and replays the entire retention window.
  *
  *   3. The job worker is a `Source.tick` materialised on the actor-system
  *      dispatcher — non-HTTP publisher, the case the bus was designed for.
  *      Each tick calls [[ReplayableBroadcastBus.publishAndAck]] so the
  *      broker assigns and persists a sequence before the frame is
  *      considered shipped.
  *
  * The `<id>` URL segment is restricted to `[a-zA-Z0-9_-]+` for the same
  * reason as [[ReplayChatRoutes]]: NATS treats `*` and `>` as wildcards. */
final class ReplayJobsRoutes(
    bus: ReplayableBroadcastBus,
    tickInterval: FiniteDuration = 200.millis,
    defaultSteps: Int = 100
)(using system: ActorSystem[?]):

  private given ec: scala.concurrent.ExecutionContext = system.executionContext

  private val IdSegment = "[a-zA-Z0-9_-]+".r

  private def topicFor(id: String): String = s"jsjob:$id"

  /** Tracks job ids we have started, so the listing page knows what exists. */
  private val started = TrieMap.empty[String, Unit]

  val routes: Route =
    concat(
      pathPrefix("jetstream-jobs") {
        concat(
          pathEndOrSingleSlash {
            concat(
              get {
                CsrfSupport.withCsrfToken { csrf =>
                  complete(views.html.jetstream.jobs(started.keys.toVector.sorted, csrf))
                }
              },
              post {
                CsrfSupport.withCsrfToken { csrf =>
                  CsrfSupport.requireCsrf(csrf) {
                    val id = UUID.randomUUID().toString.take(8)
                    started.update(id, ())
                    start(id, defaultSteps)
                    redirect(s"/jetstream-jobs/$id", StatusCodes.SeeOther)
                  }
                }
              }
            )
          },
          path(IdSegment) { id =>
            get {
              CsrfSupport.withCsrfToken { csrf =>
                extractRequest { req =>
                  val scheme = if req.uri.scheme == "https" then "wss" else "ws"
                  val wsUrl  = s"$scheme://${req.uri.authority}/jetstream-streams/jobs/$id"
                  complete(views.html.jetstream.job(csrf, id, wsUrl))
                }
              }
            }
          }
        )
      },
      path("jetstream-streams" / "jobs" / IdSegment) { id =>
        parameter("last_seq".as[Long].optional) { lastSeq =>
          val sanitisedLastSeq = lastSeq.filter(_ >= 0L)
          val frames: Source[(Long, String), NotUsed] =
            bus.subscribeFrom(topicFor(id), sanitisedLastSeq)
          val outbound: Source[Message, NotUsed] =
            frames.map { case (seq, html) => TextMessage(SeqStamping.stamp(html, seq)) }
          val flow = Flow.fromSinkAndSourceCoupled(Sink.ignore, outbound)
          handleWebSocketMessages(flow)
        }
      }
    )

  /** Worker: same tick shape as [[hotwire.JobsRoutes.start]], but uses
    * `publishAndAck` so the broker-assigned seq stamps every frame. */
  private[jetstream] def start(id: String, totalSteps: Int): Unit =
    Source.tick(0.millis, tickInterval, ())
      .scan(0)((n, _) => n + 1)
      .takeWhile(_ <= totalSteps, inclusive = true)
      .runForeach { step =>
        val rawFrag =
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
        // publishAndAck blocks for the broker ack, so the seq is in hand
        // before the next tick fires. Out-of-order delivery cannot happen
        // because there is one publisher per job and tick is serial.
        bus.publishAndAck(topicFor(id), rawFrag)
        ()
      }(using system)
    ()
```

Note: `_progress.scala.html` and `_progress_done.scala.html` from PR #1 are
reused as-is. If PR #2 ships before PR #1, copy those two templates first.

### Twirl templates

**`src/main/twirl/views/jetstream/jobs.scala.html`** — listing page.

```html
@(items: Seq[String], csrfToken: String)
@views.html.layout("Replay jobs", csrfToken) {
  <header>
    <h1>JetStream replay jobs</h1>
    <p class="hint">
      Same shape as <a href="/jobs">/jobs</a>, but progress frames are persisted in
      JetStream. Open a job, disable the network, re-enable: the bar resumes from where
      it would have been, not where it was when the WS dropped.
    </p>
  </header>

  <form action="/jetstream-jobs" method="post" class="compose">
    <input type="hidden" name="_csrf" value="@csrfToken">
    <button type="submit">Start a job</button>
  </form>

  <ul class="jobs">
    @for(id <- items) {
      <li><a href="/jetstream-jobs/@id">Job @id</a></li>
    }
    @if(items.isEmpty) {
      <li class="hint">No jobs yet — start one above.</li>
    }
  </ul>
}
```

**`src/main/twirl/views/jetstream/job.scala.html`** — single-job page.
Renders empty; the broker drives the rest, exactly as
`jetstream/chat.scala.html` does.

```html
@(csrfToken: String, id: String, wsUrl: String)
@views.html.layout("Replay job: " + id, csrfToken) {
  <header>
    <h1>JetStream replay job — @id</h1>
    <p class="hint">
      Open in two tabs. Disable network on one, watch the other tick to ~30%, then
      re-enable: the offline tab backfills via JetStream and catches up. The page
      intentionally renders empty on first load — every frame arrives via the
      WebSocket, so the broker is the single source of truth.
    </p>
  </header>

  <replaying-turbo-stream-source
    data-src="@wsUrl"
    data-room="@id"></replaying-turbo-stream-source>

  <div id="job-@(id)-progress" class="job-progress"></div>

  <p><a href="/jetstream-jobs">All replay jobs</a></p>

  <script type="module" src="/public/jetstream/replay.js"></script>
}
```

### `Main.scala` — wire `ReplayJobsRoutes`

```diff
     val replayBus: Option[ReplayableBroadcastBus] =
       (demo, natsUrl, jsStreamName) match
         case (Demo.Posts, _, _)              => None
         case (_, Some(url), Some(name)) =>
           log.info(s"Connecting JetStream replay bus at $url (stream=$name)")
           Some(
             new JetStreamBroadcastBus(
-              JetStreamBroadcastBus.connectAndEnsureStream(url, streamName = name, subjectsWildcard = "jschat.>"),
+              JetStreamBroadcastBus.connectAndEnsureStream(
+                url,
+                streamName       = name,
+                subjectsWildcards = Seq("jschat.>", "jsjob.>")
+              ),
               streamName = name
             )
           )
         case _ => None

     val chatRoutes:    Option[Route] = …
-    val replayRoutes:  Option[Route] = replayBus.map(b => new ReplayChatRoutes(b).routes)
+    val replayRoutes:  Option[Route] = replayBus.map(b => new ReplayChatRoutes(b).routes)
+    val replayJobsRoutes: Option[Route] =
+      replayBus
+        .filter(_ => demo == Demo.All || demo == Demo.Jobs || demo == Demo.Chat)
+        .map(b => new ReplayJobsRoutes(b).routes)
     val postsRoutes:   Option[Route] = …
     val jobsRoutes:    Option[Route] = …
```

(The `filter` mirrors how `ReplayChatRoutes` is gated implicitly by
`replayBus`. Replay-jobs reuses the same bus, so it ships whenever the bus
is up *and* the demo selector includes jobs or chat. If you'd rather
always-on, drop the `.filter` — the route just sits there inert if no
client opens it.)

```diff
     val mounted: Seq[Route] =
-      chatRoutes.toSeq ++ replayRoutes.toSeq ++ postsRoutes.toSeq ++ jobsRoutes.toSeq ++ Seq(
+      chatRoutes.toSeq ++ replayRoutes.toSeq ++ replayJobsRoutes.toSeq ++ postsRoutes.toSeq ++ jobsRoutes.toSeq ++ Seq(
```

The startup banner already mentions the chat replay; extend it for jobs:

```diff
-        val replayHint = if replayBus.isDefined then "  →  /jetstream-chat/lobby for replay demo" else ""
+        val replayHint =
+          if replayBus.isDefined then "  →  /jetstream-chat/lobby (chat) and /jetstream-jobs (jobs) for replay demos"
+          else ""
```

### Tests

JetStream needs a real broker — no automated test in CI, mirroring
`ReplayChatRoutes`. The existing `SeqStampingSpec` tests already cover the
seq-stamping idempotence and the user-content-collision case. No new test
files needed for PR #2; the `Main.scala` wiring is structural.

**Manual recipe** (run after PR #1 has shipped so the templates exist):

1. `nats-server -js --store_dir /tmp/jshotwire`
2. ```
   NATS_URL=nats://localhost:4222 \
   NATS_JS_STREAM=CHAT_REPLAY \
   DEMO=all sbt run
   ```
3. **Stream check.** `nats stream info CHAT_REPLAY` — expect
   `Subjects: jschat.>, jsjob.>`.
4. **Backfill check (jobs).** Browser tab A: open
   `http://localhost:8080/jetstream-jobs`, click "Start a job". Wait until
   the bar reaches ~20%. Open DevTools → Network → throttle "Offline" for
   ~5 seconds. Re-enable. The bar should jump to current step, not stay
   stuck at 20%, and continue ticking.
5. **Cross-tab check.** Open the same job URL in tab B (a *new* tab — not
   a copy of tab A). It should backfill the entire job history (because
   `sessionStorage` for the new tab starts at `lastSeq=0`).
6. **Subject isolation.** Post a chat message at `/jetstream-chat/lobby`,
   then `nats stream view CHAT_REPLAY --filter jsjob.>` should show only
   job frames, no chat frames; reverse with `--filter jschat.>`.

### Validation steps

1. `sbt compile` — clean.
2. `sbt test` — `SeqStampingSpec` passes with the new import path; nothing
   else changes.
3. `sbt run` (no env vars) — replay routes are absent (just like today);
   chat + posts + jobs from PR #1 work.
4. `NATS_URL=nats://localhost:4222 NATS_JS_STREAM=CHAT_REPLAY sbt run`
   against `nats-server -js` — `/jetstream-chat/lobby` and
   `/jetstream-jobs` both mount; manual recipe steps 4–6 succeed.
5. Existing chat-replay flow unchanged: send a message at
   `/jetstream-chat/lobby` from one tab, reload another tab, see the
   message backfill. (Regression check that the multi-subject stream
   change didn't break the existing demo.)

### Done-when

- [ ] `SeqStamping` lives at top level under `hotwire.examples.jetstream`;
      `ReplayChatRoutes` imports it; `SeqStampingSpec` passes with the new
      import.
- [ ] `connectAndEnsureStream` accepts `subjectsWildcards: Seq[String]`;
      `Main.scala` passes `Seq("jschat.>", "jsjob.>")`.
- [ ] `ReplayJobsRoutes` mounts under `/jetstream-jobs/<id>`; WS at
      `/jetstream-streams/jobs/<id>?last_seq=N`.
- [ ] Manual recipe steps 4–6 succeed in Chrome (and Firefox if reachable).
- [ ] Existing chat-replay regression check (validation step 5) passes.

### Rollback plan

- `git revert <pr2-commit>` removes `ReplayJobsRoutes`, the new templates,
  the multi-subject helper change, and the `SeqStamping` extraction.
- The on-broker stream named `CHAT_REPLAY` keeps its extended subject list
  after revert; this is harmless (no publisher remains for `jsjob.>` so
  nothing accumulates). Optionally narrow it: `nats stream edit
  CHAT_REPLAY --subjects 'jschat.>'`.
- If only the multi-subject helper is wrong: leave the keep `Seq` overload,
  but switch `Main.scala` back to `Seq("jschat.>")` — `ReplayJobsRoutes`
  will just see no traffic.

---

## Part 3 — README updates (PR #3)

### Scope

Documentation only. No code, no config.

### Files to edit

| Path | Action |
| --- | --- |
| `README.md` | edit |

### Changes

1. **"What's in here" table** — add `/jobs` (PR #1) and, if PR #2 has
   shipped, `/jetstream-jobs` to the list of demos.

2. **New section "Switching to JetStream replay (jobs)"** — point at the
   existing `Switching to NATS JetStream` chat recipe and document the
   jobs equivalent:

   ```markdown
   ### Verifying replay survives a network blip

   Run a JetStream-enabled broker and start the app:

   ```bash
   nats-server -js --store_dir /tmp/jshotwire
   NATS_URL=nats://localhost:4222 \
   NATS_JS_STREAM=CHAT_REPLAY \
   sbt run
   ```

   Open <http://localhost:8080/jetstream-jobs>, click "Start a job", and
   watch the bar tick. Around 20% open DevTools → Network → throttle
   "Offline" for a few seconds. Re-enable. The bar jumps to its current
   step rather than staying stuck — the broker held the missed frames,
   the `<replaying-turbo-stream-source>` element reconnected with
   `?last_seq=N`, and the JetStream consumer replayed them in order.
   ```

3. **"What's deliberately not here"** — drop or edit the JetStream bullet
   to reflect that JetStream is now in `hotwire.examples.jetstream` (PR #4
   already covered chat; this plan adds jobs).

### Validation steps

1. Visual inspection of the rendered README on GitHub (or `glow README.md`).
2. Manually walk the new "Verifying replay" recipe on a clean machine.

### Done-when

- [ ] README's "What's in here" lists the new demo(s).
- [ ] New replay recipe section under the existing "Switching to NATS
      JetStream" content.
- [ ] All commands in the recipe run as written against a fresh broker.

### Rollback plan

`git revert <pr3-commit>`. README-only; zero blast radius.

---

## Rollout order

1. **PR #1 — Background-job progress bar on `BroadcastBus`.** No NATS
   dependency, fully testable in CI, ships value immediately. Independent
   of PR #4.
2. **PR #2 — Replay-aware progress bar on `ReplayableBroadcastBus`.**
   Depends on PR #1's templates (`_progress`, `_progress_done`) and
   PR #4's existing `ReplayableBroadcastBus` / `replay.js` /
   `JetStreamBroadcastBus`. Pure addition; default behaviour
   (without `NATS_JS_STREAM`) unchanged.
3. **PR #3 — README updates.** Depends on PRs #1 and #2.

PR #1 can land before PR #2 with no extra coordination. If PR #2 lands
without PR #1, the templates `_progress.scala.html` and
`_progress_done.scala.html` need to be lifted into PR #2 itself.
