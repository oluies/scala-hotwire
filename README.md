# scala-hotwire

[![CI](https://github.com/oluies/scala-hotwire/actions/workflows/ci.yml/badge.svg)](https://github.com/oluies/scala-hotwire/actions/workflows/ci.yml)

A minimal but real Hotwire (Turbo Streams) backend in Scala 3, on top of Pekko HTTP.
The goal is to demonstrate the **pub/sub façade** pattern: write the app once against a
`BroadcastBus` trait, swap the implementation between in-process Pekko Streams and NATS
without touching the routes.

## What is Hotwire?

[Hotwire](https://hotwired.dev/) — short for "**H**TML **O**ver **T**he **WIRE**" — is
37signals' alternative to building SPAs. The server renders HTML, the browser receives
HTML, and small JavaScript libraries swap fragments of that HTML into the live DOM.
There is no JSON API and no client-side routing. The wire format is the rendered page.

The design philosophy in one paragraph (paraphrased from
[hotwired.dev](https://hotwired.dev/)):

> **Send HTML, not JSON.** Server-rendered HTML is the simplest possible format the
> browser already understands. Most of what an SPA hand-rolls — routing, state
> reconciliation, optimistic updates, JSON schemas — disappears when the server
> stays in charge of HTML and the client just patches the DOM with what arrives
> over a request or a WebSocket.

Hotwire ships as three loosely-coupled pieces; this project only needs the first two:

| Component                                                  | What it does                                                                              |
| ---------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| **[Turbo](https://turbo.hotwired.dev/handbook/introduction)** | Drives navigation, partial updates (Frames), and live broadcasts (Streams) — no JSON.  |
| **[Stimulus](https://stimulus.hotwired.dev/handbook/introduction)** | Tiny controllers for sprinkles of behaviour on existing HTML. Optional here.        |
| **[Strada](https://strada.hotwired.dev/)**                 | Bridges Turbo apps into native iOS/Android shells. Out of scope for this project.         |

**Turbo Streams** — the part this project leans on — is documented in detail in
the [Turbo handbook → Streams](https://turbo.hotwired.dev/handbook/streams) and
the [reference for `<turbo-stream>` actions](https://turbo.hotwired.dev/reference/streams).
The wire format is intentionally tiny: a `<turbo-stream action="…" target="…"><template>…</template></turbo-stream>`
element delivered either as the body of a `text/vnd.turbo-stream.html` HTTP response or
as a UTF-8 text frame on a WebSocket. That is the entire protocol — see the
[Turbo source for `stream_observer.js`](https://github.com/hotwired/turbo/blob/main/src/observers/stream_observer.js)
if you want to verify.

Why this matters for the Scala side: there is no client-side state to keep in sync, no
JSON schema to maintain, and no GraphQL gateway to debug. The server's only job is to
*render the right HTML at the right time*, and the bus in this project exists solely
to fan that rendered HTML out to the right subscribers.

## What's in here

```
src/main/scala/hotwire/
  BroadcastBus.scala            # the trait — `publish(topic, html)` / `subscribe(topic)`
  InProcessBroadcastBus.scala   # MergeHub → BroadcastHub per topic, anchor consumer
  NatsBroadcastBus.scala        # jnats Connection + Dispatcher, dropHead overflow
  TurboStream.scala             # text/vnd.turbo-stream.html marshaller + helpers
  CsrfSupport.scala             # double-submit cookie CSRF directive
  ChatRoutes.scala              # demo 1: chat room with form POST + WS fan-out
  PostsRoutes.scala             # demo 2: infinite-scroll feed via lazy <turbo-frame>
  Main.scala                    # boot — picks the bus based on $NATS_URL
  examples/jetstream/           # JetStream replay example — see "Advanced example" below
    ReplayableBroadcastBus.scala
    JetStreamBroadcastBus.scala
    ReplayChatRoutes.scala

src/main/twirl/views/
  layout.scala.html             # base layout, Turbo from CDN, csrf-token meta
  chat.scala.html               # the room page + <turbo-stream-source>
  _message.scala.html           # one message row
  posts.scala.html              # the feed page + first lazy <turbo-frame>
  _posts_page.scala.html        # one page-of-posts wrapped in a <turbo-frame>
  _post.scala.html              # one post card
  jetstream/chat.scala.html     # replay-chat page + <replaying-turbo-stream-source>

src/main/resources/
  application.conf              # host/port/secret/nats-url config
  logback.xml
  public/style.css
  public/jetstream/replay.js    # <replaying-turbo-stream-source> custom element

src/test/scala/hotwire/
  InProcessBroadcastBusSpec.scala
  TurboStreamSpec.scala
  ChatRoutesSpec.scala
  PostsRoutesSpec.scala
  examples/jetstream/SeqStampingSpec.scala
```

Two demos, picked to show the two halves of Hotwire:

| Demo                                | Mechanism                                     | Bus needed?         |
| ----------------------------------- | --------------------------------------------- | ------------------- |
| `/chat/<room>` — live chat          | Turbo Streams over WebSocket + form POST      | yes (broadcast bus) |
| `/posts` — infinite-scroll feed     | Lazy `<turbo-frame>` walking pages of HTML    | no — plain HTTP     |

The chat demo is the case for the bus. The feed demo is the case *against* reaching
for it by default: pagination is one-directional and ephemeral, so no WebSocket, no
broadcast, no client state — just a chain of `<turbo-frame loading="lazy">` requests
walking page by page. See the
[Cycode post on infinite-scroll pagination with Hotwire](https://cycode.com/blog/infinite-scrolling-pagination-hotwire/)
for the original Rails treatment of the pattern, and
["Hotwire's Dark Side: When Real-Time Isn't Worth It"](https://dev.to/alex_aslam/hotwires-dark-side-when-real-time-isnt-worth-it-167a)
for the cost case against putting *everything* on a stream.

## How the pub/sub façade works

```scala
trait BroadcastBus:
  def publish(topic: String, html: String): Unit
  def subscribe(topic: String): Source[String, NotUsed]
  def shutdown(): Future[Unit]
```

A "topic" is a string the application picks (e.g. `chat:lobby`, `post:42:comments`).
A "message" is a UTF-8 string — the raw `<turbo-stream …>` HTML fragment. There is no
JSON envelope, because that is exactly what `<turbo-stream-source>` wants to receive.

### `InProcessBroadcastBus`

Per topic: a `MergeHub.source[String]` (fan-in) wired to a `BroadcastHub.sink[String]`
(fan-out). New publishers get a per-producer buffer of 8; new subscribers see only
post-subscription messages. An anchor `Sink.ignore` consumer keeps the BroadcastHub
draining when there are zero real subscribers — without it, the upstream MergeHub
would back-pressure to a halt as soon as the per-topic buffer filled.

### `NatsBroadcastBus`

Publish: `connection.publish(subject, htmlBytes)` — non-blocking, internally buffered
by jnats. Topics like `chat:lobby` map to NATS subjects `chat.lobby` (`:` and `/` →
`.`).

Subscribe: a single `Dispatcher` per topic enqueues callback messages into a
`Source.queue` with `OverflowStrategy.dropHead`, fanned out via a `BroadcastHub`. A
slow Pekko subscriber chain therefore drops the oldest queued frame instead of
back-pressuring the NATS dispatcher thread (which would eventually drop the
connection).

### When to use which

| Situation                                      | Bus to pick           |
| ---------------------------------------------- | --------------------- |
| Single JVM, hobby/internal tool                | InProcess             |
| 2+ JVM nodes behind a load balancer            | NATS                  |
| Non-JVM publishers (Go/Python workers, hooks)  | NATS                  |
| Need WS-reconnect replay of missed frames      | NATS + JetStream¹     |

¹ Out of scope for this demo — see `NatsBroadcastBus.scala` for where you'd swap the
core dispatcher for a `JetStreamSubscription` with a durable consumer.

The point of the trait: you start on InProcess, and the day you outgrow one node the
swap is a one-line change in `Main.scala`. Application code never sees NATS.

## Running

Prerequisites: JDK 17+ and sbt 1.10+.

```bash
sbt run
```

Open <http://localhost:8080/chat/lobby> in two browser tabs and chat. Messages
appear in both tabs without a page reload — the synchronous Turbo Stream HTTP
response feeds the submitter, the WebSocket feeds everyone else.

Then open <http://localhost:8080/posts> and scroll. Each time the bottom
`<turbo-frame loading="lazy">` enters the viewport, Turbo fetches `/posts/page/N`,
which returns a `<turbo-frame id="posts-page-N">` containing the next ten posts
*plus* a fresh lazy placeholder for page N+1. The chain walks itself until the last
page returns no further placeholder. Open the network panel to confirm: it's a
sequence of plain `text/html` GETs, not a single long-lived stream.

### Picking which demo to mount

`sbt run` mounts both demos on a single server. To focus on one in isolation, set
the `DEMO` env var:

```bash
DEMO=all   sbt run    # default — both demos (chat + posts)
DEMO=chat  sbt run    # only /chat/<room>; / redirects to /chat/lobby; no PostsRoutes
DEMO=posts sbt run    # only /posts;       / redirects to /posts;       no bus, no chat
```

`DEMO=posts` is genuinely lighter: it skips `BroadcastBus` construction entirely (no
in-process hub, and crucially no NATS connection attempt even if `NATS_URL` is set),
since the feed demo never publishes or subscribes. Useful when you're poking at
turbo-frames and don't want the WebSocket / bus machinery in the picture.

### Infinite-scroll wire pattern

```html
<!-- on the page after first render -->
<turbo-frame id="posts-page-2" loading="lazy" src="/posts/page/2"></turbo-frame>

<!-- /posts/page/2 returns -->
<turbo-frame id="posts-page-2">
  …10 post cards…
  <turbo-frame id="posts-page-3" loading="lazy" src="/posts/page/3"></turbo-frame>
</turbo-frame>
```

Turbo matches frames by id, so the response's `posts-page-2` frame's children
replace the existing `posts-page-2` frame's children. Those children include a
*new* lazy frame with a different id, which is what triggers the next fetch. The
last page renders no inner placeholder, terminating the chain.

### Switching to NATS

Run a NATS server (one binary, no config file required):

```bash
brew install nats-server   # or download from nats.io
nats-server                 # listens on 4222
```

Then start the app pointed at it:

```bash
NATS_URL=nats://localhost:4222 sbt run
```

To prove fan-out across nodes, run two app instances on different ports:

```bash
NATS_URL=nats://localhost:4222 PORT=8080 sbt run
NATS_URL=nats://localhost:4222 PORT=8081 sbt run
```

Open <http://localhost:8080/chat/lobby> in one tab and <http://localhost:8081/chat/lobby>
in another. A message posted on either node fans out to subscribers on both.

## Advanced example: JetStream with reconnect-replay

A second chat lives at `/jetstream-chat/<room>` to demonstrate the most-asked
production WS question: *what happens to messages a tab misses while its socket
is closed?* Answer here: nothing, the tab backfills on reconnect.

### How it works

* **Bus** — `examples/jetstream/JetStreamBroadcastBus.scala` implements a separate
  `ReplayableBroadcastBus` trait. `publishAndAck` blocks for the broker ack and
  returns the assigned JetStream stream sequence; `subscribeFrom(topic, Some(n))`
  opens an *ephemeral* JetStream consumer with `DeliverPolicy.ByStartSequence`
  starting at `n + 1`, so a single subscription replays the backlog and then
  transitions to the live tail.
* **Stamping** — every `<turbo-stream>` fragment that reaches the browser carries
  `data-seq="N"` (see `ReplayChatRoutes.SeqStamping`). The synchronous form-POST
  response uses the seq returned by `publishAndAck`; broadcast frames use the seq
  the consumer reports for each delivered message.
* **Client** — `public/jetstream/replay.js` defines a
  `<replaying-turbo-stream-source>` custom element that (a) tracks the highest
  `data-seq` it has rendered in `localStorage` keyed by room, and (b) reconnects
  with `?last_seq=N` on close with exponential backoff. No Stimulus or other JS
  framework is pulled in.
* **No per-tab server state** — the browser is the source of truth for "what
  have I seen". The server just replays from whatever seq it's asked for.

### Running

Start a NATS server with JetStream enabled:

```bash
nats-server -js
```

Run the app pointed at it, with a stream name to activate the example:

```bash
NATS_URL=nats://localhost:4222 NATS_JS_STREAM=CHAT_REPLAY sbt run
```

Open <http://localhost:8080/jetstream-chat/lobby> in two tabs.

### Validating replay (manual)

1. Open `/jetstream-chat/lobby` in **tab A** and **tab B**.
2. Post a message from A — both tabs render it. The DOM fragment carries `data-seq`.
3. In tab B, open devtools → Network tab → switch to "Offline" (this also closes
   the WebSocket).
4. Post 2-3 messages from tab A. Tab B does *not* see them (offline).
5. Switch tab B back to "Online". The custom element reconnects with
   `?last_seq=N`, and tab B backfills the missed messages, in order.

You can also confirm the replay window is bounded by the stream's
`maxMessagesPerSubject` / `maxAge` config — older messages beyond the retention
limit are not replayed.

### Storage trade-offs

Stream defaults: `maxMessagesPerSubject=1000`, `maxAge=24h`, file-backed. Tune via
`JetStreamBroadcastBus.connectAndEnsureStream` arguments. For a true durable chat
log you'd switch to subject-keyed Key-Value or a real DB; for ephemeral
"reconnect within a few minutes" replay, the defaults are fine.

### What this example does *not* do

* **Per-user authorization on replay.** A tab can request `?last_seq=0` and get
  the full retained history. Add auth + a per-room ACL in front before exposing
  this on the public internet.
* **Durable consumers.** Each reconnect creates and tears down an ephemeral
  consumer. For thousands of concurrent subscribers, switch to one durable
  consumer per `(user, topic)` and have the server track ack seqs.
* **Catch-up rate limiting.** A tab that backfills 10k messages will see them
  arrive as fast as JetStream can deliver. Add a `Throttle` stage on the
  replay source if backfills can be large.

## Tests

```bash
sbt test
```

The `InProcessBroadcastBusSpec` exercises subscribe-after, topic isolation, multi-
subscriber fan-out, and the post-subscription delivery semantic. `TurboStreamSpec`
covers the wrapper format and attribute escaping. `ChatRoutesSpec` covers the CSRF
directive and the `text/vnd.turbo-stream.html` content type. `SeqStampingSpec`
exercises the `data-seq` insertion used by the JetStream replay example.

NATS and JetStream aren't covered by automated tests because they require a running
broker; run the two-node and replay manual procedures above to validate.

## Wire-protocol cheatsheet

What Turbo expects on a Turbo Stream WebSocket frame
([source](https://github.com/hotwired/turbo/blob/main/src/observers/stream_observer.js)):

* a UTF-8 **text** WebSocket frame
* whose payload is one or more `<turbo-stream action="…" target="…"><template>…</template></turbo-stream>` elements

That's the entire protocol. There is no subscribe/confirm handshake, no JSON
envelope, no Action Cable framing. `<turbo-stream-source src="ws://…">` opens the
socket and pipes every text frame straight into Turbo's stream observer.

## What's deliberately not here

* **Authentication / sessions.** The CSRF cookie alone is enough to demonstrate the
  Hotwire mechanics. Add `softwaremill/akka-http-session` (Pekko fork:
  `pjfanning/pekko-http-session`) when you need real auth.
* **Database.** `ChatRoutes` keeps history in a `TrieMap`. Plug Slick / Doobie /
  Magnum where the comment says "your DB".
* **Stimulus controllers.** Turbo alone covers the broadcast/append flow shown here.
  Add Stimulus when you need per-element JS behaviour (e.g. "auto-scroll to bottom
  on new message"). It's pure client-side and unrelated to the server design.
* **JetStream durable consumers.** The advanced example uses *ephemeral* consumers
  with start-sequence — fine for "tab reconnects within retention window" replay,
  but not for at-least-once delivery to a known cohort. Switch to one durable
  consumer per `(user, topic)` and persist ack seqs server-side when you need that.
* **Origin checking on the WS upgrade.** For production behind a public origin, add
  a `headerValueByName("Origin")` check in `streams/chat/<room>` and reject mismatches.

## Why Pekko, not Akka

Akka 2.7+ is BSL, Pekko is the Apache 2.0
fork of Akka 2.6.x, actively maintained, with API parity at the package level
(`akka.…` → `org.apache.pekko.…`). Pick Pekko unless you have a paid Akka
subscription.
