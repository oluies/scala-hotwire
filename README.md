# scala-hotwire

[![CI](https://github.com/oluies/scala-hotwire/actions/workflows/ci.yml/badge.svg)](https://github.com/oluies/scala-hotwire/actions/workflows/ci.yml)

A minimal but real Hotwire (Turbo Streams) backend in Scala 3, on top of Pekko HTTP.
The goal is to demonstrate the **pub/sub façade** pattern: write the app once against a
`BroadcastBus` trait, swap the implementation between in-process Pekko Streams and NATS
without touching the routes.

## What's in here

```
src/main/scala/hotwire/
  BroadcastBus.scala            # the trait — `publish(topic, html)` / `subscribe(topic)`
  InProcessBroadcastBus.scala   # MergeHub → BroadcastHub per topic, anchor consumer
  NatsBroadcastBus.scala        # jnats Connection + Dispatcher, dropHead overflow
  TurboStream.scala             # text/vnd.turbo-stream.html marshaller + helpers
  CsrfSupport.scala             # double-submit cookie CSRF directive
  ChatRoutes.scala              # demo: chat room with form POST + WS fan-out
  Main.scala                    # boot — picks the bus based on $NATS_URL

src/main/twirl/views/
  layout.scala.html             # base layout, Turbo from CDN, csrf-token meta
  chat.scala.html               # the room page + <turbo-stream-source>
  _message.scala.html           # one message row

src/main/resources/
  application.conf              # host/port/secret/nats-url config
  logback.xml
  public/style.css

src/test/scala/hotwire/
  InProcessBroadcastBusSpec.scala
  TurboStreamSpec.scala
  ChatRoutesSpec.scala
```

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

## Tests

```bash
sbt test
```

The `InProcessBroadcastBusSpec` exercises subscribe-after, topic isolation, multi-
subscriber fan-out, and the post-subscription delivery semantic. `TurboStreamSpec`
covers the wrapper format and attribute escaping. `ChatRoutesSpec` covers the CSRF
directive and the `text/vnd.turbo-stream.html` content type.

NATS isn't covered by automated tests because it requires a running broker; run the
two-node manual procedure above to validate.

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
* **JetStream durable consumers.** Add a `JetStreamBroadcastBus` implementation when
  you need replay-on-reconnect. The trait stays the same.
* **Origin checking on the WS upgrade.** For production behind a public origin, add
  a `headerValueByName("Origin")` check in `streams/chat/<room>` and reject mismatches.

## Why Pekko, not Akka

Akka 2.7+ is BSL with a US$25M-revenue commercial trigger. Pekko is the Apache 2.0
fork of Akka 2.6.x, actively maintained, with API parity at the package level
(`akka.…` → `org.apache.pekko.…`). Pick Pekko unless you have a paid Akka
subscription.
