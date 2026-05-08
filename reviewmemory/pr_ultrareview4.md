# Ultrareview findings — PR #4

Two passes against `oluies/scala-hotwire#4` ("Add JetStream replay example
with reconnect-aware client").

- **Pass 1**: five findings (bug_001, bug_007, bug_010, bug_014, merged_bug_002),
  all addressed in commit `35295c7`.
- **Pass 2**: three follow-up findings (bug_001, bug_003, bug_004), addressed
  in a follow-up commit. The pass-2 bug_001 is a *different* bug from pass-1
  bug_001 — it's a regression visible only after the pass-1 bug_010 fix
  switched `data-initial-seq` to a broker-global query.

---

# Pass 1

---

## bug_001 — SeqStamping idempotency check matches user content

- **Severity:** normal
- **File:** `src/main/scala/hotwire/examples/jetstream/ReplayChatRoutes.scala:132-140`
- **Status:** fixed in `35295c7`

### PR comment

The `SeqStamping.stamp` idempotency guard `fragment.contains("data-seq=")`
matches the substring anywhere in the fragment, including user-controlled
message bodies inside the `<template>`. Twirl HTML-escapes `<`, `>`, `&`, and
quotes but **not** `=`, so a user who posts a body like `Hi data-seq= world`
causes `stamp` to short-circuit and return the fragment without ever stamping
the outer `<turbo-stream>` — both the form-POST response (line 89) and WS
broadcast frames (line 109) are unstamped, the JS regex `/data-seq="(\d+)"/g`
finds no match, `localStorage` never advances, and on reconnect the message
replays indefinitely. Fix by anchoring the check to the opening tag (e.g.
test only the slice up to the first `>` after `<turbo-stream `, or use
`fragment.startsWith("<turbo-stream data-seq=")`).

### Reasoning

`ReplayChatRoutes.SeqStamping.stamp` treats *any* occurrence of `data-seq=`
anywhere in the fragment as proof that the outer `<turbo-stream>` has already
been stamped:

```scala
def stamp(fragment: String, seq: Long): String =
  if fragment.contains("data-seq=") then fragment
  else
    val idx = fragment.indexOf(needle)
    ...
```

The fragment, however, embeds user content inside `<template>...</template>`.
Twirl HTML-escapes `<`, `>`, `&`, `"`, and `'`, but it does **not** escape `=`
or alphanumerics. So a chat message body of `Hi data-seq= world` survives
intact through `sanitise(body).take(500)` and lands inside
`<span class="body">Hi data-seq= world</span>` in `views.html._message`. The
guard then matches that substring and returns the fragment unchanged — the
outer `<turbo-stream>` never gets the attribute.

#### Step-by-step proof

1. User posts a message with body `Hi data-seq= world` to
   `/jetstream-chat/lobby/messages`.
2. `sanitise("Hi data-seq= world").take(500)` → `"Hi data-seq= world"`
   (no control chars to strip, `=` is preserved).
3. `views.html._message(msg)` renders
   `<span class="body">Hi data-seq= world</span>`. Twirl escapes `<`, `>`,
   `&`, quotes — `=` is unaffected.
4. `TurboStream.stream(Append, "messages", content)` wraps it:
   `<turbo-stream action="append" target="messages"><template>...<span class="body">Hi data-seq= world</span>...</template></turbo-stream>`.
5. `bus.publishAndAck(...)` returns, say, seq `42`.
6. `SeqStamping.stamp(fragment, 42)` runs `fragment.contains("data-seq=")` —
   `true` (the substring occurs inside `<span class="body">`).
7. The function returns the fragment **unchanged**. The outer `<turbo-stream>`
   opens with `action="append" target="messages">...`, no `data-seq` attribute.
8. The HTTP response carries that unstamped fragment back to the submitter;
   `replay.js` finds zero matches for `/data-seq="(\d+)"/g` and does not
   update `localStorage`.
9. The WS broadcast for the same message goes through the same `stamp` call
   and is also unstamped, so every other tab also fails to advance.
10. The submitter closes the tab, reopens minutes later. `data-initial-seq`
    is whatever the latest stamped seq was *before* this message, say `41`.
    The custom element reconnects with `?last_seq=41`. `subscribeFrom(topic,
    Some(41L))` opens an ephemeral consumer at seq `42`, which delivers the
    same offending message; `replaySource` stamps it via the same broken
    `stamp`, the fragment is again unstamped, localStorage stays at `41`,
    and the next reconnect repeats the cycle.

#### Why tests do not catch it

`SeqStampingSpec` only ever passes `Html("<p>hi</p>")` or `Html("ignored")` as
the template body — neither contains `data-seq=`. The idempotency test reuses
the *output* of `stamp` itself, which is correctly already-stamped, so it
exercises the intended idempotency path but never the user-content-collision
path.

#### Fix

Anchor the check to the opening tag. Test only the slice between
`<turbo-stream ` and the first `>`:

```scala
val idx = fragment.indexOf(needle)
if idx < 0 then fragment
else
  val tagEnd = fragment.indexOf('>', idx + needle.length)
  if tagEnd >= 0 && fragment.substring(idx, tagEnd).contains("data-seq=") then fragment
  else
    val insertAt = idx + needle.length
    val (before, after) = fragment.splitAt(insertAt)
    s"""${before}data-seq="$seq" $after"""
```

---

## bug_010 — Initial seq is stale on nodes that have not seen local traffic

- **Severity:** normal
- **File:** `src/main/scala/hotwire/examples/jetstream/ReplayChatRoutes.scala:50-65`
- **Status:** fixed in `35295c7`

### PR comment

On a node where no message has been posted locally and no WS subscriber is
currently attached, `latestSeqFor(room)` is never bumped, so a fresh tab
renders with `data-initial-seq="0"`. The replay JS then omits `?last_seq=`
(since `last > 0 ? ... : url`), the WS opens with `subscribeFrom(topic, None)`
→ live-tail (`DeliverPolicy.New`), and the entire historical backlog is
silently skipped. This breaks the documented multi-node replay scenario and a
single-node restart (in-memory state). Fix: bump `latestSeqFor` inside the
live-hub MessageHandler in `buildLiveHub` (where `seq` is already in scope), or
query JetStream stream-info when rendering the page.

### Reasoning

`latestSeqFor(room)` is the value used to render `data-initial-seq` on the
page. The browser uses this attribute to decide whether to open the WebSocket
with `?last_seq=N` (and thus receive a backfill) or without it (and thus
subscribe to the live tail only). It is updated in exactly two places:

1. `ReplayChatRoutes.scala:88` — after `bus.publishAndAck(...)` in the
   form-POST handler. Only fires for messages posted *to this node*.
2. `ReplayChatRoutes.scala:117` — inside the per-WS `frames.map`. Only fires
   when an outbound frame flows through an active WS subscriber.

Neither path bumps the seq when JetStream messages arrive on the live-hub from
another node while no WS subscriber is currently attached.

`JetStreamBroadcastBus.buildLiveHub` builds a `BroadcastHub` whose anchor
consumer is `raw.runWith(Sink.ignore)`. The JetStream `MessageHandler` receives
`(seq, payload)` and offers it into the queue, but does NOT touch
`latestSeqFor`. So on a node where no WS subscriber is currently attached,
JetStream messages drain straight into `Sink.ignore` with no side effect on
the seq state.

Worse: `liveHubFor` uses `ConcurrentHashMap.computeIfAbsent`, so until the
first WS subscriber attaches, the live hub does not even exist and JetStream
is not being read on this node at all.

#### Step-by-step proof (multi-node)

1. Two app instances A and B share JetStream stream `CHAT_REPLAY`.
2. A user posts a message to B → `B.publishAndAck` returns seq=10;
   `B.latestSeqFor("lobby")` becomes 10.
3. On node A, `latestSeqFor("lobby")` is still 0 (no local POST happened, and
   assume no WS subscriber is currently attached).
4. A new browser tab loads `/jetstream-chat/lobby` on node A.
   `ReplayChatRoutes.scala:61` reads `latestSeqFor(room).get()` → returns `0`.
5. The page renders `<replaying-turbo-stream-source data-initial-seq="0" ...>`.
6. `replay.js` `connectedCallback` reads `parseInt(this.dataset.initialSeq ||
   "0", 10)` → 0; `localStorage` is empty for this room, so `#getSeq()`
   returns 0.
7. `#connect` evaluates `last > 0 ? this.url + "?last_seq=" + last : this.url`
   → `last=0`, so the URL is the bare WS URL.
8. The server-side route handler parses `last_seq` as `None` and calls
   `bus.subscribeFrom(topic, None)` → `liveHubFor(topic)` → `DeliverPolicy.New`.
9. The new tab subscribes only to the live tail, never sees seq=10, and the
   entire historical backlog is silently missed.

#### Single-node failure mode

Same bug breaks single-node restart. `latestSeqs` is a `TrieMap[String,
AtomicLong]` held in memory only — after a restart it is empty, but JetStream
still has the messages. Any new visitor without `localStorage` state will
render with `data-initial-seq="0"` and skip the entire retained history.

---

## bug_014 — Shared localStorage causes silent message loss across tabs

- **Severity:** normal
- **File:** `src/main/resources/public/jetstream/replay.js:16-30`
- **Status:** fixed in `35295c7`

### PR comment

The `<replaying-turbo-stream-source>` element stores the highest-rendered seq
in **localStorage** keyed only by room (`lastSeq:${room}`), but localStorage
is shared across all tabs of the same origin — "highest seq THIS tab has
rendered" is tab-local state. Two tabs in the same room corrupt each other's
progress marker: tab A renders seqs 50–100 and writes `lastSeq:lobby=100`;
tab B (whose WS was closed/throttled) reconnects, reads `100`, requests
`?last_seq=100`, and the server replays only seq>100 — silently losing 50–100
from B's DOM. This directly breaks the README's two-tab walkthrough
("backfills the missed messages, in order, no duplicates"). Fix: use
`sessionStorage` (per-tab, persists across reload of the same tab), or scope
the key to a tab id captured into `sessionStorage` on first load and combine
with localStorage as a first-load hint only.

### Reasoning

`replay.js` defines a custom element that writes the maximum seq it has ever
observed into `localStorage` under `lastSeq:${room}`. The architectural
assumption is that this represents "what this tab has rendered" — but
`localStorage` is **per-origin**, not per-tab. Every tab on
`http(s)://host:port` for that room shares the same key and writes are
immediately visible to the others.

#### Step-by-step trigger

| Step | Tab A action | Tab B action | localStorage `lastSeq:lobby` | Tab B's DOM has? |
|------|--------------|--------------|-------------------------------|------------------|
| 1 | open page (seq=49) | open page (seq=49) | 49 | …, 49 |
| 2 | online | devtools → Offline (WS closes) | 49 | …, 49 |
| 3 | post msg (seq=50) | (offline, reconnect loop) | **50** (written by A) | …, 49 |
| 4 | post msg (seq=51) | (offline) | **51** (written by A) | …, 49 |
| 5 | post msg (seq=52) | (offline) | **52** (written by A) | …, 49 |
| 6 | — | devtools → Online → `#connect` reads 52 → opens `?last_seq=52` | 52 | …, 49 *(50–52 NEVER arrive)* |

The server's ephemeral consumer starts at `lastSeq + 1 = 53`, so seqs 50–52
are silently dropped from B's view. README step 5 ("tab B backfills the
missed messages, in order") fails.

#### Fix

`sessionStorage` is per-tab and persists across reloads of the same tab —
exactly the right semantics for "highest seq this tab has rendered". Simplest
fix: replace `localStorage` with `sessionStorage` in `#getSeq` / `#setSeq`.

---

## merged_bug_002 — Dispatcher/stream leaks when js.subscribe throws

- **Severity:** normal
- **File:** `src/main/scala/hotwire/examples/jetstream/JetStreamBroadcastBus.scala:79-107`
- **Status:** fixed in `35295c7`

### PR comment

Both subscribe paths in `JetStreamBroadcastBus` allocate resources eagerly and
then call `js.subscribe` without protecting the cleanup edge:

1. `buildLiveHub` materializes a queue+BroadcastHub via `.run()`, anchors with
   `Sink.ignore`, creates a `Dispatcher`, then calls `js.subscribe` with no
   try/catch — if subscribe throws, the materialized stream keeps running and
   the dispatcher is never closed.
2. `replaySource` creates the dispatcher *before* the try block and only
   registers the cleanup callback (`watchCompletion().onComplete`) *inside*
   the try after `js.subscribe` — if subscribe throws, the catch only does
   `queue.fail(t)` and the dispatcher leaks.

The replay path is reachable from any client because `ReplayChatRoutes.scala:102`
parses `?last_seq=N` as a raw `Long` with no validation, so a buggy or hostile
client driving repeated reconnects with bad start sequences will accumulate one
leaked dispatcher (and on the live path, one running stream) per failure. Fix
both spots: wrap `js.subscribe` in try/catch, and on failure call
`connection.closeDispatcher(dispatcher)` plus `queue.complete()`/`queue.fail(t)`
before propagating.

### Reasoning

The bus has two paths into JetStream and both leak on `js.subscribe` failure,
but they leak differently.

**`buildLiveHub`** is the worst of the two. It allocates four things in
sequence before subscribing:

1. `Source.queue(...).toMat(BroadcastHub.sink(...))(Keep.both).run()` — the
   queue+hub stream is *running* once `.run()` returns.
2. `raw.runWith(Sink.ignore)` — an anchor consumer is attached so the
   BroadcastHub keeps draining when there are zero real subscribers.
3. `connection.createDispatcher()` — a NATS dispatcher (with its own
   thread/internal queue in jnats) is created.
4. `js.subscribe(subject, dispatcher, handler, false, pso)` — *no try/catch
   around this*.

If subscribe throws, the lambda passed to `liveHubs.computeIfAbsent` throws.
`ConcurrentHashMap.computeIfAbsent` correctly does *not* cache the failed
mapping — but it also doesn't clean up anything the lambda allocated. The
result: the materialized BroadcastHub stream keeps running with the
`Sink.ignore` anchor draining it forever, and the dispatcher is never closed.
`shutdown()` only iterates `liveHubs`, which is empty after the failed insert,
so even process shutdown won't reach these. The leak ends only when
`connection.close()` runs.

**`replaySource`** has subtler asymmetry:

```scala
val dispatcher = connection.createDispatcher()  // BEFORE try
...
try
  js.subscribe(subject, dispatcher, handler, false, pso)
  queue.watchCompletion().onComplete { _ =>     // registers cleanup
    try connection.closeDispatcher(dispatcher)
    catch case _: Throwable => ()
  }
catch
  case t: Throwable =>
    queue.fail(t)                                // no closeDispatcher
```

The cleanup hook (`watchCompletion`) is registered *after* `js.subscribe`. If
that throws, control jumps to the catch — which only calls `queue.fail(t)`.
The `watchCompletion` callback was never wired up, and the catch block
doesn't close the dispatcher. One NATS dispatcher (and its dedicated thread)
leaks per failure.

#### Why `js.subscribe` can throw

- Stream missing or renamed (e.g. operator deleted/recreated `CHAT_REPLAY`)
- Subject doesn't match the stream's configured `subjectsWildcard`
- `startSequence` past the stream tail or below first_seq — broker rejects
- Connection dropped during the subscribe handshake
- `maxConsumers` limit hit on the stream
- Permission errors

The `replaySource` trigger is *especially* reachable: `ReplayChatRoutes.scala:102`
parses `last_seq.as[Long].optional` with no validation. A hostile or buggy
client (or just a client whose `localStorage` got corrupted) can hammer
reconnects with values like `Long.MaxValue` or sequences far past the stream
head, each one leaking a dispatcher. The `replay.js` client even reconnects
automatically with exponential backoff, so one bad `localStorage` value
drains resources continuously until the tab is closed.

#### Fix

Mirror the `watchCompletion` cleanup in the catch path. For `replaySource`:

```scala
catch
  case t: Throwable =>
    try connection.closeDispatcher(dispatcher) catch case _: Throwable => ()
    queue.fail(t)
```

For `buildLiveHub`, wrap the subscribe in a try/catch and on failure call
`connection.closeDispatcher(dispatcher)` and `queue.complete()` (which also
terminates the materialized stream and releases the `Sink.ignore` anchor),
then rethrow so `computeIfAbsent` doesn't cache a half-built entry.

A cleaner alternative is to register `watchCompletion` *before* `js.subscribe`
in `replaySource` so `queue.fail(t)` alone drives the dispatcher cleanup; for
`buildLiveHub` the same trick works if `queue.complete()` is called in the
catch.

---

## bug_007 — Default subjectsWildcard collides with NatsBroadcastBus subjects

- **Severity:** nit
- **File:** `src/main/scala/hotwire/examples/jetstream/JetStreamBroadcastBus.scala:188-204`
- **Status:** fixed in `35295c7`

### PR comment

`JetStreamBroadcastBus.connectAndEnsureStream` defaults `subjectsWildcard` to
`"chat.>"`, which overlaps with the subjects already used by
`NatsBroadcastBus` (it maps `chat:room` → `chat.room`). `Main.scala` overrides
this with `"jschat.>"` so the running app is fine, but anyone copy-pasting the
helper into their own app and calling it with defaults while a core-NATS chat
is also active on the same NATS connection will silently capture every
core-bus chat publish into the persistent stream. Recommend changing the
default to `"jschat.>"` (matching what the demo actually wires up) and
updating the class scaladoc on line 51, which still claims "a single
JetStream stream covers `chat.>`".

### Reasoning

The core-NATS bus (`NatsBroadcastBus`) maps every chat topic of the form
`chat:room` onto the NATS subject `chat.room`. The default JetStream wildcard
`chat.>` therefore covers exactly those subjects.

NATS JetStream attaches to subjects on the underlying NATS connection. As
soon as a stream with subjects=`chat.>` exists on the server, **every**
publish on `chat.lobby`, `chat.foo`, etc. — including those done by the plain
`NatsBroadcastBus` — is captured and persisted into that stream. A consumer
of the JetStream side then sees both intentional `jschat`/replay traffic *and*
the core-NATS chat traffic, doubling storage and producing surprising replay
payloads.

The class-level scaladoc says "a single JetStream stream covers `chat.>` ..."
but `Main.scala` actually invokes the helper with `subjectsWildcard = "jschat.>"`.
The doc is stale relative to the demo wiring.

#### Suggested fix

- Change the default `subjectsWildcard: String = "chat.>"` to `"jschat.>"`
  (and likewise consider `streamName: String = "CHAT_REPLAY"` to align with
  the README).
- Update the class-level scaladoc to match (`jschat.>`), or drop the
  specific subject mention and refer to the constructor argument.

---

# Pass 2

Three findings on commit `35295c7`. The pass-2 bug_001 is a regression
introduced by the pass-1 bug_010 fix: switching `data-initial-seq` to a
broker-global query (`jsm.getLastMessage`) made the seq attribute correct,
but the rendered HTML still came from the per-node `rooms` TrieMap, so the
two diverged silently.

Resolved in a follow-up commit by:
- bug_004: wrap `Turbo.renderStreamMessage` in try/catch and bump
  `sessionStorage` only on success; load `replay.js` as `type="module"` so it
  runs after the deferred Turbo module.
- bug_003: restrict `<room>` URL segment to `[a-zA-Z0-9_-]+` at the route
  boundary (regex-typed PathMatcher), and reject NATS wildcard chars `*`/`>`
  in `JetStreamBroadcastBus.topicToSubject` as defense in depth.
- bug_001: drop server-side history entirely. `ReplayChatRoutes.rooms` and
  `data-initial-seq` are gone; the page renders an empty `#messages` div, and
  `replay.js` always sends `?last_seq=N` (including N=0, which JetStream
  interprets as "from the start of the retention window"). The broker is the
  single source of truth. `ReplayableBroadcastBus.latestSeq` and the bus's
  `seqCache` + `jsm` field are also dropped — no caller needed them anymore.

---

## bug_004 — Seq bumped before Turbo.renderStreamMessage; silent message loss if Turbo module not yet evaluated

- **Severity:** normal
- **File:** `src/main/resources/public/jetstream/replay.js:56-67`
- **Status:** fixed in pass-2 commit

### PR comment

Silent message loss when initial WS frames arrive before Turbo module
evaluates. `replay.js` is loaded as a classic `<script src>` at body-end
(`chat.scala.html:29`) while Turbo is loaded as `<script type="module">` in
the head (`layout.scala.html:10`) — module scripts are deferred, so the
classic script (and the WebSocket it opens via `connectedCallback`) runs
*before* Turbo is defined. If a backfill frame arrives in that window, lines
61-64 bump `sessionStorage` to the message's seq, then line 66 throws
`ReferenceError: Turbo is not defined` — the exception is uncaught, the
message is never rendered, and the next reconnect sends `?last_seq=N` so
JetStream's `ByStartSequence` consumer skips that message permanently. Fix:
wrap `Turbo.renderStreamMessage` in a `try` and only `#setSeq` on success
(or load `replay.js` as `type="module"` so it runs after Turbo).

### Reasoning

The message handler updates `sessionStorage` with the highest `data-seq` it
observed in `e.data` *before* calling `Turbo.renderStreamMessage(e.data)`.
The block comment at lines 58-60 explains this ordering: it preserves the
seq even when Turbo accepts the fragment but its `before-stream-render` hook
never fires (e.g. the duplicate-id case). That justification is sound *for
the case where Turbo is loaded* and the call returns normally. It does not
cover the case where the call itself throws.

#### How the race is set up

Two script tags determine the timing:

- `layout.scala.html:10` — `<script type="module" src="…turbo.es2017-esm.js">`.
  Per the HTML spec, module scripts have implicit defer — they fetch in
  parallel but execute only after the document is parsed.
- `chat.scala.html:29` — `<script src="/public/jetstream/replay.js">` at the
  end of body. Classic, no `defer`/`async` — blocks parsing and runs
  synchronously when the parser reaches it.

So at runtime:

1. Parser kicks off the Turbo module fetch (parallel, deferred execution).
2. Parser walks the body, sees `<replaying-turbo-stream-source data-initial-seq="N" …>`
   (an unrecognized tag — placeholder, no upgrade yet).
3. Parser hits `<script src="…replay.js">`, blocks, fetches, evaluates.
4. `customElements.define("replaying-turbo-stream-source", …)` runs at the
   bottom of the file. The element on the already-parsed page upgrades
   *synchronously*, calling `connectedCallback` → `#connect()` →
   `new WebSocket(url)`.
5. Parser finishes the document.
6. The deferred Turbo module finally executes; `window.Turbo` becomes defined.

Between step 4 and step 6 the WebSocket exists but `Turbo` does not. On a
fresh tab with `data-initial-seq > 0` (the most common backfill case after a
tab reload during an active chat), the server starts replaying immediately
on connect, so frames can land in exactly that window.

#### Step-by-step proof

Assume tab loaded with `data-initial-seq=10`, broker has messages 11 and 12
ready to replay, Turbo being fetched cold from the CDN (~200-500 ms is
realistic).

1. `t=0`: parser hits `replay.js`, defines the element, `connectedCallback` runs.
2. `t=10ms`: WebSocket handshake completes, server opens an ephemeral
   consumer at `startSequence=11`.
3. `t=15ms`: server pushes frame for seq 11
   (`<turbo-stream data-seq="11" …>…</turbo-stream>`).
4. `t=15ms`: handler runs:
   - Regex matches `data-seq="11"`, `seqs = [11]`, `max = 11`.
   - `#getSeq()` returns 10, so `#setSeq(11)` runs —
     `sessionStorage["lastSeq:lobby"] = "11"`.
5. `t=15ms`: line 66 calls `Turbo.renderStreamMessage(e.data)`.
   - `Turbo` is `undefined` (the module has not executed yet).
   - `ReferenceError: Turbo is not defined` is thrown synchronously inside
     the event listener.
   - Browser logs to console, no further user-visible signal.
6. `t=300ms`: Turbo module finishes executing. Subsequent frames render fine.
7. **Later**: user closes/sleeps the tab. WebSocket closes. On reconnect,
   `last = #getSeq() = 11`, URL is `…?last_seq=11`. The bus opens a new
   ephemeral consumer at `startSequence = 12`.
8. **Message 11 is gone forever**, even though JetStream still has it.

#### Fix

Two changes in concert:

```js
try {
  Turbo.renderStreamMessage(e.data);
} catch (err) {
  console.error("renderStreamMessage failed; not advancing seq", err);
  return;
}
// only after a successful render:
if (seqs.length) {
  const max = Math.max(...seqs);
  if (max > this.#getSeq()) this.#setSeq(max);
}
```

Plus loading `replay.js` as `<script type="module">` so it runs after the
Turbo module has finished executing — addressing the race at the source.

---

## bug_003 — Wildcard chars in `<room>` segment leak across all rooms via NATS subject filter injection

- **Severity:** normal
- **File:** `src/main/scala/hotwire/examples/jetstream/ReplayChatRoutes.scala:92-107`
- **Status:** fixed in pass-2 commit

### PR comment

Wildcard NATS subject characters (`*`, `>`) in the `<room>` URL segment leak
through to the JetStream consumer's `filterSubject`, letting an
unauthenticated client open `/jetstream-streams/chat/%3E?last_seq=0` and
exfiltrate the entire retained history of every room in the stream. The
README's "no per-user auth on replay" disclaimer scopes the threat to a
single room's own history; this defect crosses room boundaries. Fix at the
route boundary by validating `<room>` against `[a-zA-Z0-9_-]+` (or
escape/reject `*` and `>` in `topicToSubject`).

### Reasoning

`ReplayChatRoutes` accepts a user-supplied `<room>` segment via Pekko's
`Segment` matcher and passes it verbatim through `topicFor` (`s"jschat:$room"`)
into `bus.subscribeFrom`. `JetStreamBroadcastBus.topicToSubject` only rewrites
`:` and `/` to `.`, so the NATS wildcard characters `*` (single token) and
`>` (multi-token) survive unmodified into `js.subscribe(subject, dispatcher,
handler, false, pso)`. JetStream consumers honour wildcards in `filterSubject`
— that is exactly the same syntax used by the stream's own
`subjects("jschat.>")` clause in `connectAndEnsureStream`. The route also lets
`?last_seq=0` through (`lastSeq.filter(_ >= 0L)`), which the bus turns into
`startSequence(1)`, replaying from the first retained message.

#### Step-by-step proof

1. Attacker opens a WebSocket to `/jetstream-streams/chat/%3E?last_seq=0`.
   Pekko's `Segment` URL-decodes `%3E` to `>`, giving `room=">"`.
2. Route calls `bus.subscribeFrom(topicFor(room), Some(0))`. `topicFor`
   returns `"jschat:>"`.
3. In `JetStreamBroadcastBus.subscribeFrom`, `Some(0)` matches the third
   arm and dispatches to `replaySource(topic, 0L)`.
4. `replaySource` computes `subject = topicToSubject("jschat:>") = "jschat.>"`
   and builds an ephemeral consumer with `DeliverPolicy.ByStartSequence` and
   `startSequence(0 + 1) = 1`.
5. `js.subscribe("jschat.>", dispatcher, handler, false, pso)` registers a
   JetStream push consumer whose `filterSubject` is the wildcard `jschat.>`.
   JetStream replays every message in the stream — across every room —
   beginning at seq 1.
6. Each delivered `<turbo-stream …>` fragment is sent over the WebSocket.
   Within minutes the attacker has every chat fragment ever published to any
   room within the 24h × 1000-msg-per-subject retention window.

The single-token variant `/jetstream-streams/chat/%2A` (`room="*"`) yields
filter `jschat.*`, which still matches every room since each room is exactly
one token under `jschat.`.

#### Why existing safeguards don't help

- `sanitise` is only applied to `author` and `body`, not to `room`.
- `Segment` URL-decodes any non-slash characters, including `*` and `>`.
- `topicToSubject` is wildcard-unaware — it only handles `:` and `/`.
- The route's `_ >= 0L` predicate accepts `0`, which the bus turns into
  `startSequence(1)` (a full replay of retained history).
- A publish on `*` or `>` would be rejected by NATS, but only the *subscribe*
  path is needed for exfiltration.

#### Fix

Validate `room` at the route boundary against a strict charset
(`[a-zA-Z0-9_-]+`), e.g. by passing a `Regex` where the route currently uses
`Segment` (Pekko's PathMatcher accepts a `Regex` as a `PathMatcher1[String]`
that matches only segments matching the regex). Apply to both
`pathPrefix("jetstream-chat" / Segment)` and `path("jetstream-streams" /
"chat" / Segment)`. As defense in depth, also reject `*` and `>` in
`JetStreamBroadcastBus.topicToSubject` so the bus is robust against any
caller that forgets to validate.

---

## bug_001 (pass 2) — `data-initial-seq` uses broker-global max but server-rendered history is per-node, causing silent message loss

- **Severity:** normal
- **File:** `src/main/scala/hotwire/examples/jetstream/ReplayChatRoutes.scala:50-60`
- **Status:** fixed in pass-2 commit (Approach A — drop server-side history)

### PR comment

On a fresh tab the page renders the per-node in-memory `history(room)` (a
TrieMap mutated only by this JVM's POST handler) but stamps `data-initial-seq`
with `bus.latestSeq(topic)`, which queries the broker-global highest seq via
JetStream's `getLastMessage`. `replay.js` writes that high seq into
sessionStorage, then connects with `?last_seq=N`, so the consumer's
`startSequence(N+1)` has nothing to deliver and the user silently loses every
message that exists on the broker but isn't in this node's local Vector.
This triggers on every JVM restart (TrieMap empty, JetStream still retains
24h), every multi-node deployment (rooms is per-node), and every cold node
before any local POST — exactly the scenarios the JetStream replay feature is
marketed to fix. Fix: stamp `data-initial-seq` with the highest seq actually
rendered server-side, or set it to 0 and skip server-rendered history (let WS
replay paint everything from seq 1).

### Reasoning

The page handler renders **two pieces of data from independent sources**:

1. The visible message list comes from `history(room)` — a per-process
   `TrieMap[String, Vector[ChatMessage]]` mutated only by *this* JVM's POST
   handler. It never gets backfilled from the broker.
2. `data-initial-seq` comes from `bus.latestSeq(topicFor(room))`, which in
   `JetStreamBroadcastBus.latestSeq` falls through to
   `jsm.getLastMessage(streamName, subject)` — i.e. the **broker-global**
   highest seq, including messages this node has never observed.

These two sources can diverge, and when they do the client is told it has
already seen messages that were never rendered into its DOM.

`replay.js` raises `sessionStorage[lastSeq:room]` whenever the rendered
`data-initial-seq` is greater than the current value (always true on a fresh
tab where it is 0). Then the WS opens with `?last_seq=N`, and
`JetStreamBroadcastBus.replaySource` builds an ephemeral consumer with
`DeliverPolicy.ByStartSequence` at `N+1`. Anything ≤ N is skipped at the
broker. There is no error indication to the user — the room just looks empty
or stale.

#### Single-JVM restart proof

1. Start app with `NATS_JS_STREAM=CHAT_REPLAY`. POST msg1 and msg2 to
   `/jetstream-chat/lobby`. Both land in JetStream (seq=1, seq=2) and in the
   local `rooms` TrieMap.
2. Kill and restart the JVM. JetStream retains both messages; the in-memory
   `rooms` is empty.
3. Open `/jetstream-chat/lobby` in a new tab.
   - `history(lobby)` → `Vector.empty` → page renders zero `<message>` nodes.
   - `bus.latestSeq("jschat:lobby")` → seqCache empty → falls through to
     `jsm.getLastMessage` → returns 2.
   - Page rendered with `data-initial-seq="2"` and an empty `#messages` div.
4. `replay.js` `connectedCallback`: `getSeq()` is 0, `initial` is 2, so
   `setSeq(2)` writes `lastSeq:lobby=2` to sessionStorage.
5. `#connect()`: `last=2`, URL becomes `…/jetstream-streams/chat/lobby?last_seq=2`.
6. Server opens consumer at `startSequence(3)`. Broker has nothing ≥ 3. WS
   sits idle.
7. **User sees an empty chat room despite both messages being retained on
   the broker, and will never see them on this tab** — sessionStorage will
   keep them suppressed across reloads of this tab too.

#### Multi-node trigger

Node A POSTs msg1, msg2; Node B POSTs msg3. Visiting Node A renders
`[msg1, msg2]` with `data-initial-seq=3` (because A's liveHub has consumed
msg3 from JetStream and bumped `seqCache`, *or* the fallback `getLastMessage`
returns 3). The browser sets sessionStorage to 3, requests `last_seq=3`,
never receives msg3.

#### Fix (chosen: Approach A)

Don't render server-side history at all. Stamp nothing for `data-initial-seq`
(remove the attribute) and have `replay.js` always send `?last_seq=N`,
including N=0. The bus already treats `Some(0)` as "from start of retention"
via `replaySource(topic, 0)` → `startSequence(1)`, so a fresh tab backfills
the entire retention window via the WebSocket.

This preserves the README's stated invariant that the browser is the source
of truth for "what have I seen" — the prior code violated it by also
maintaining a server-side `rooms` Vector that could drift from the broker.
With server-side history dropped, `ReplayableBroadcastBus.latestSeq` and the
JetStream bus's `seqCache` + `jsm` field are also no longer needed and were
removed.

Trade-off: the page renders empty for ~50-200 ms before WS frames arrive on
first load. For an example whose entire point is the broker-as-source-of-truth
mechanism, this honesty is a feature rather than a regression.
