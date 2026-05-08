# Plan: `morph` action + turbo_power-style server-driven actions

Two related additions to `hotwire.TurboStream` plus one demo per feature. Both broaden
the *wire surface area* the server can drive without adding any client-side framework
beyond what Turbo 8 already ships (we add ~50 lines of vanilla JS, no build step).

References:
- Turbo 8 morph: <https://turbo.hotwired.dev/handbook/page_refreshes>
- `turbo-morph` (Rails port that introduced the action): <https://github.com/hopsoft/turbo-morph>
- `turbo_power` (extra stream actions): <https://github.com/marcoroth/turbo_power-rails>

Decisions baked into this plan (resolving open questions from the previous draft):

1. **Custom-action targeting uses *standard* `target="<id>"` semantics.** No
   `target="window"` / `target="body"` override. Demos that need a "no-element"
   action use a real DOM placeholder id (`#redirect-anchor` for `redirect`,
   `#event-bus` for `dispatch_event`). One CSS rule hides them. This keeps
   `turbo-power.js` to one screenful and matches Turbo's own contract.
2. **`turbo-power.js` is a classic script with `defer`** that listens for
   `turbo:load`. The Turbo CDN tag is `type="module"`, which already runs
   *after* `defer` classic scripts (modules are deferred-by-spec), so by the
   time our `DOMContentLoaded`/`turbo:load` handler fires `Turbo` is on
   `window`. No polling, no `import` (the unpkg ESM build doesn't expose
   `StreamActions` as a named export anyway).
3. **`Action.Redirect` is `private[hotwire]`.** Only the `TurboStream.redirect`
   helper (which returns a `String` callers must put in an HTTP response) can
   construct it. We add a unit test that asserts the enum case is unreachable
   from outside the package, preventing accidental `bus.publish(_,
   redirectFragment)` cross-tab navigation.
4. **`dispatch_event` `detail` is a `String`.** No JSON dependency. Callers
   that want to ship structured data JSON-encode upstream (or use `play-json`
   in their own module). The helper HTML-attribute-escapes whatever it gets;
   the JS does `JSON.parse` on the unescaped attribute. We document this on
   the helper and test the round-trip with a hand-encoded JSON string.
5. **JS testing strategy:** Scala-side, snapshot-test exact rendered strings
   for every helper. JS-side, no test framework — a manual browser recipe
   ships in PR #2's `Done-when` checklist.

## Part 1 — `morph` stream action

### Why

The four "core" actions (`append`, `prepend`, `replace`, `update`) all *blow away*
the target subtree. That nukes form state, focus, scroll position, and any
client-side mutation. `morph` is an idempotent patch driven by
[idiomorph](https://github.com/bigskysoftware/idiomorph) (bundled in Turbo 8): it
diffs the new HTML against the live DOM and only changes nodes/attributes that
actually differ.

Wire format is identical to other stream actions — only the `action` attribute
changes:

```html
<turbo-stream action="morph" target="message-42">
  <template>…the new HTML for #message-42…</template>
</turbo-stream>
```

`action="refresh"` (full-page morph) is layout-level and out of scope.

---

## PR #1 — `Morph` enum case

### Files to edit

- `src/main/scala/hotwire/TurboStream.scala`
- `src/test/scala/hotwire/TurboStreamSpec.scala`

### Concrete diffs

**`src/main/scala/hotwire/TurboStream.scala`** — extend the `Action` enum. The
existing `stream()` `_ =>` branch already wraps content in `<template>`, which
is exactly what `morph` expects, so no other code change is needed.

```diff
   enum Action(val name: String):
     case Append  extends Action("append")
     case Prepend extends Action("prepend")
     case Replace extends Action("replace")
     case Update  extends Action("update")
     case Remove  extends Action("remove")
     case Before  extends Action("before")
     case After   extends Action("after")
+    /** Idempotent DOM patch via Turbo 8's bundled idiomorph. Preserves form
+      * state, focus, scroll, and any client-side DOM mutation that doesn't
+      * conflict with the morph target. Use this instead of [[Replace]] when
+      * the target subtree contains live form inputs the user is editing. */
+    case Morph   extends Action("morph")
```

**`src/test/scala/hotwire/TurboStreamSpec.scala`** — add one test mirroring the
existing `Append` case:

```scala
test("morph wraps content in a turbo-stream/template element") {
  val s = TurboStream.stream(
    action = TurboStream.Action.Morph,
    target = "message-42",
    content = Html("<div id=\"message-42\">edited</div>")
  )
  assertEquals(
    s,
    """<turbo-stream action="morph" target="message-42"><template><div id="message-42">edited</div></template></turbo-stream>"""
  )
}
```

### Validation steps

```
sbt compile
# expect: [success] Total time: ...

sbt "testOnly hotwire.TurboStreamSpec"
# expect: 6 tests passed (was 5)
```

### Done-when

- [ ] `Action.Morph` exists and renders `action="morph"` with a `<template>`.
- [ ] `TurboStreamSpec` passes with one new test asserting the exact rendered
  string.
- [ ] `sbt compile` clean (no `-Wunused` warnings introduced).
- [ ] No other file changed.

### Rollback plan

Revert the diff. Zero call sites yet, zero behaviour change for existing code.
A single `git revert <sha>` removes it cleanly.

---

## Part 2 — `turbo_power`-style server-driven actions

### Why

Turbo's spec has 7 actions. `turbo_power` ships ~25 more by treating
`<turbo-stream>` as a generic server-to-client RPC envelope. Custom actions
register on the client with `Turbo.StreamActions.foo = function() { … }` and
then any `<turbo-stream action="foo">` runs that function.

Initial set (kept small on purpose):

| Action            | Effect                                              | Demo it unlocks                          |
| ----------------- | --------------------------------------------------- | ---------------------------------------- |
| `redirect`        | `Turbo.visit(href)`                                 | Server-pushed nav after long job        |
| `notification`    | Append a toast into the `#toasts` region            | Cross-tab "Bob joined the room"         |
| `set_attribute`   | `el.setAttribute(name, value)`                      | Disable a button while job runs         |
| `remove_attribute`| `el.removeAttribute(name)`                          | Re-enable it                            |
| `add_css_class`   | `el.classList.add(...classes)`                      | Highlight new message                   |
| `remove_css_class`| `el.classList.remove(...classes)`                   | Un-highlight after 2s                   |
| `dispatch_event`  | `el.dispatchEvent(new CustomEvent(name, {detail}))` | Hook for any Stimulus controller        |

Wire format conventions:

```html
<!-- attribute-only actions: no <template>; data passed as attrs -->
<turbo-stream action="set_attribute" target="submit-btn"
              attribute="disabled" value="true"></turbo-stream>

<turbo-stream action="add_css_class" target="message-7"
              classes="flash"></turbo-stream>

<turbo-stream action="redirect" target="redirect-anchor"
              url="/posts"></turbo-stream>

<turbo-stream action="dispatch_event" target="event-bus"
              name="job:done" detail='{&quot;id&quot;:42}'></turbo-stream>

<!-- content actions: <template> as usual -->
<turbo-stream action="notification" target="toasts">
  <template><div class="toast">Saved.</div></template>
</turbo-stream>
```

---

## PR #2 — `streamAttrs` plumbing + `turbo-power.js` + layout wiring

Pure plumbing, no demo behaviour. Lands the new `Action` cases, the JS
registry, the layout `<script>` include, and the `#toasts`/`#redirect-anchor`/
`#event-bus` placeholders. After this PR the chat demo behaviour is unchanged
(no caller invokes the new helpers yet).

### Files to create / edit

- **edit** `src/main/scala/hotwire/TurboStream.scala`
- **edit** `src/test/scala/hotwire/TurboStreamSpec.scala`
- **create** `src/main/resources/public/turbo-power.js`
- **edit** `src/main/twirl/views/layout.scala.html`
- **edit** `src/main/resources/public/style.css`

### Concrete snippets

**`src/main/scala/hotwire/TurboStream.scala`** — extend `Action`, add
`streamAttrs`, add typed helpers.

Add cases inside `enum Action`:

```scala
  // turbo_power-style custom actions. Require client-side registration
  // (see `src/main/resources/public/turbo-power.js`).
  case Notification    extends Action("notification")
  case SetAttribute    extends Action("set_attribute")
  case RemoveAttribute extends Action("remove_attribute")
  case AddCssClass     extends Action("add_css_class")
  case RemoveCssClass  extends Action("remove_css_class")
  case DispatchEvent   extends Action("dispatch_event")

  /** Hidden so callers can't broadcast it on the bus and yank every tab
    * to the same URL. Use [[TurboStream.redirect]] in an HTTP response. */
  private[hotwire] case Redirect extends Action("redirect")
```

Add helpers below `streams`:

```scala
  /** Render a turbo-stream element with no `<template>` payload — data is
    * conveyed via attributes. Used by the turbo_power-style custom actions
    * (see `turbo-power.js`). */
  def streamAttrs(action: Action, target: String, attrs: (String, String)*): String =
    val rendered =
      attrs.map((k, v) => s"""$k="${escapeAttr(v)}"""").mkString(" ")
    val sep = if rendered.isEmpty then "" else " "
    s"""<turbo-stream action="${action.name}" target="${escapeAttr(target)}"$sep$rendered></turbo-stream>"""

  /** Server-pushed `Turbo.visit(url)`. Targets the hidden `#redirect-anchor`
    * placeholder in the layout — `target` is required by Turbo's stream
    * machinery even though `redirect` ignores the element.
    *
    * '''Never publish the result on a [[BroadcastBus]] topic.''' Doing so
    * would navigate every subscribed tab. Return it from an HTTP route or
    * (only if you really mean it) emit on a per-user topic. The
    * [[Action.Redirect]] enum case is `private[hotwire]` to make accidental
    * misuse a compile error from outside the package. */
  def redirect(url: String): String =
    streamAttrs(Action.Redirect, "redirect-anchor", "url" -> url)

  /** Append toast HTML into a flash region (typically `#toasts`). */
  def notification(target: String, content: Html): String =
    stream(Action.Notification, target, content)

  def setAttribute(target: String, name: String, value: String): String =
    streamAttrs(Action.SetAttribute, target,
      "attribute" -> name, "value" -> value)

  def removeAttribute(target: String, name: String): String =
    streamAttrs(Action.RemoveAttribute, target, "attribute" -> name)

  def addCssClass(target: String, classes: String): String =
    streamAttrs(Action.AddCssClass, target, "classes" -> classes)

  def removeCssClass(target: String, classes: String): String =
    streamAttrs(Action.RemoveCssClass, target, "classes" -> classes)

  /** Dispatch a `CustomEvent` on the targeted element.
    *
    * `detail` is a raw string. To ship structured data, JSON-encode it
    * upstream (this module avoids a JSON dep). The string is HTML-attribute-
    * escaped here; the client `JSON.parse`s the unescaped attribute. If you
    * pass a non-JSON string, the client side will throw — pass `""` (the
    * default) for a `null` detail. */
  def dispatchEvent(target: String, name: String, detail: String = ""): String =
    val base = Seq("name" -> name)
    val all  = if detail.isEmpty then base else base :+ ("detail" -> detail)
    streamAttrs(Action.DispatchEvent, target, all*)
```

**`src/test/scala/hotwire/TurboStreamSpec.scala`** — add snapshot tests:

```scala
test("streamAttrs renders attribute-only turbo-stream with no <template>") {
  val s = TurboStream.streamAttrs(
    TurboStream.Action.SetAttribute, "submit-btn",
    "attribute" -> "disabled", "value" -> "true"
  )
  assertEquals(
    s,
    """<turbo-stream action="set_attribute" target="submit-btn" attribute="disabled" value="true"></turbo-stream>"""
  )
  assert(!s.contains("<template>"))
}

test("redirect helper renders url + redirect-anchor target") {
  assertEquals(
    TurboStream.redirect("/posts"),
    """<turbo-stream action="redirect" target="redirect-anchor" url="/posts"></turbo-stream>"""
  )
}

test("addCssClass renders a single classes attribute") {
  assertEquals(
    TurboStream.addCssClass("message-7", "flash highlight"),
    """<turbo-stream action="add_css_class" target="message-7" classes="flash highlight"></turbo-stream>"""
  )
}

test("removeCssClass mirrors addCssClass") {
  assertEquals(
    TurboStream.removeCssClass("message-7", "flash"),
    """<turbo-stream action="remove_css_class" target="message-7" classes="flash"></turbo-stream>"""
  )
}

test("setAttribute escapes attribute values") {
  val s = TurboStream.setAttribute("x", "data-x", """a"<b>""")
  assert(s.contains("""value="a&quot;&lt;b&gt;""""), s)
}

test("dispatchEvent without detail omits the attribute") {
  assertEquals(
    TurboStream.dispatchEvent("event-bus", "job:done"),
    """<turbo-stream action="dispatch_event" target="event-bus" name="job:done"></turbo-stream>"""
  )
}

test("dispatchEvent escapes a JSON-encoded detail string") {
  // Caller is responsible for JSON encoding; helper only HTML-attribute escapes.
  val s = TurboStream.dispatchEvent("event-bus", "job:done", """{"id":42}""")
  assert(s.contains("""detail="{&quot;id&quot;:42}""""), s)
}

test("notification wraps content in <template> like append") {
  val s = TurboStream.notification("toasts", Html("<div class=\"toast\">hi</div>"))
  assertEquals(
    s,
    """<turbo-stream action="notification" target="toasts"><template><div class="toast">hi</div></template></turbo-stream>"""
  )
}
```

**`src/main/resources/public/turbo-power.js`** — new file. Classic script,
loaded with `defer`. Registers handlers on `turbo:load` (fires once on the
initial page load and again after each Turbo Drive visit, so registrations
survive navigation). Modules already deferred-by-spec means our `defer` script
runs *before* the Turbo module finishes evaluating in some browsers — gating
on `turbo:load` makes the ordering deterministic.

```js
// Server-driven custom <turbo-stream> actions, à la turbo_power.
// Loaded as a classic deferred script after the Turbo ESM CDN tag.
// Registration is gated on the first `turbo:load` so we don't race the
// module that defines `window.Turbo`.
(function () {
  let registered = false

  function register() {
    if (registered) return
    if (!window.Turbo || !Turbo.StreamActions) {
      // Turbo not ready yet; turbo:load will fire again.
      return
    }
    registered = true

    // ---- attribute-only actions ----------------------------------------

    Turbo.StreamActions.redirect = function () {
      const url = this.getAttribute("url")
      if (url) Turbo.visit(url)
    }

    Turbo.StreamActions.set_attribute = function () {
      const name  = this.getAttribute("attribute")
      const value = this.getAttribute("value")
      if (!name) return
      for (const el of this.targetElements) el.setAttribute(name, value)
    }

    Turbo.StreamActions.remove_attribute = function () {
      const name = this.getAttribute("attribute")
      if (!name) return
      for (const el of this.targetElements) el.removeAttribute(name)
    }

    Turbo.StreamActions.add_css_class = function () {
      const classes = (this.getAttribute("classes") || "")
        .split(/\s+/).filter(Boolean)
      if (classes.length === 0) return
      for (const el of this.targetElements) el.classList.add(...classes)
    }

    Turbo.StreamActions.remove_css_class = function () {
      const classes = (this.getAttribute("classes") || "")
        .split(/\s+/).filter(Boolean)
      if (classes.length === 0) return
      for (const el of this.targetElements) el.classList.remove(...classes)
    }

    Turbo.StreamActions.dispatch_event = function () {
      const name = this.getAttribute("name")
      if (!name) return
      let detail = null
      if (this.hasAttribute("detail")) {
        try { detail = JSON.parse(this.getAttribute("detail")) }
        catch (e) { console.warn("turbo-power dispatch_event: invalid JSON detail", e); return }
      }
      for (const el of this.targetElements)
        el.dispatchEvent(new CustomEvent(name, { detail, bubbles: true }))
    }

    // ---- content actions -----------------------------------------------

    Turbo.StreamActions.notification = function () {
      const tmpl = this.templateContent
      for (const el of this.targetElements) {
        const node = tmpl.cloneNode(true)
        el.append(node)
        // Auto-dismiss after 3s. Find the first child element we just added.
        const added = el.lastElementChild
        if (added) setTimeout(() => added.remove(), 3000)
      }
    }
  }

  document.addEventListener("turbo:load", register)
  // Fallback for the very first page if turbo:load already fired.
  if (window.Turbo) register()
})()
```

**`src/main/twirl/views/layout.scala.html`** — add the script include after
the Turbo CDN tag, and the three placeholder elements at the start of `<body>`:

```diff
     <script type="module" src="https://cdn.jsdelivr.net/npm/@@hotwired/turbo@@8.0.5/dist/turbo.es2017-esm.js"></script>
+    <script src="/public/turbo-power.js" defer></script>
     <script>
       // Reset forms after a successful Turbo Stream submission.
       document.addEventListener("turbo:submit-end", e => {
         if (e.detail && e.detail.success) e.target.reset()
       })
     </script>
   </head>
   <body>
+    <div id="toasts" class="toasts" aria-live="polite"></div>
+    <span id="redirect-anchor" class="tp-anchor" aria-hidden="true"></span>
+    <span id="event-bus" class="tp-anchor" aria-hidden="true"></span>
     @content
   </body>
```

**`src/main/resources/public/style.css`** — append:

```css
/* turbo-power custom-action placeholders. Hidden but present in the DOM so
   <turbo-stream target="…"> can resolve them. */
.tp-anchor { display: none; }

/* Toast region rendered by Turbo.StreamActions.notification. */
.toasts {
  position: fixed;
  top: 1rem;
  right: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  z-index: 1000;
  pointer-events: none;
}
.toasts .toast {
  background: var(--accent);
  color: white;
  padding: 0.6rem 0.9rem;
  border-radius: 6px;
  box-shadow: 0 2px 6px rgba(0,0,0,0.15);
  pointer-events: auto;
  animation: tp-toast-in 0.15s ease-out;
}
@keyframes tp-toast-in {
  from { opacity: 0; transform: translateY(-4px); }
  to   { opacity: 1; transform: none; }
}

/* Flash highlight applied via add_css_class / remove_css_class. */
.message.flash {
  outline: 2px solid var(--accent);
  transition: outline-color 0.4s ease-out;
}
```

### Validation steps

```
sbt compile
# expect: clean compile, no -Wunused warnings.

sbt "testOnly hotwire.TurboStreamSpec"
# expect: ~14 tests passed (5 original + 1 morph from PR#1 + 8 new here).

sbt "testOnly hotwire.ChatRoutesSpec hotwire.PostsRoutesSpec hotwire.InProcessBroadcastBusSpec"
# expect: still green; layout placeholder ids don't break anything.

# Manual JS smoke test (one-time, recipe is the JS test plan):
sbt run
# in another terminal:
curl -s http://localhost:8080/public/turbo-power.js | head -3
# expect: the IIFE banner comment.
curl -s http://localhost:8080/chat/lobby | grep -E '(turbo-power\.js|id="toasts"|id="redirect-anchor"|id="event-bus")'
# expect: all four matches.

# Browser check (no automated JS tests; document this recipe in the PR):
#   1. Open http://localhost:8080/chat/lobby in Chrome with DevTools open.
#   2. Console:
#        Turbo.StreamActions.add_css_class && "ok"
#      → "ok"
#   3. Console:
#        document.body.insertAdjacentHTML('beforeend',
#          '<turbo-stream action="add_css_class" target="toasts" classes="flash"></turbo-stream>')
#      → #toasts gains the `flash` class within a tick.
#   4. Console:
#        document.body.insertAdjacentHTML('beforeend',
#          '<turbo-stream action="notification" target="toasts"><template><div class="toast">hi</div></template></turbo-stream>')
#      → toast appears top-right, disappears after ~3s.
```

### Done-when

- [ ] `Action.{Notification,SetAttribute,RemoveAttribute,AddCssClass,RemoveCssClass,DispatchEvent}`
  exist; `Action.Redirect` is `private[hotwire]`.
- [ ] All eight new helper functions render the exact strings asserted in
  `TurboStreamSpec`.
- [ ] `turbo-power.js` is served from `/public/turbo-power.js` and registers
  on `turbo:load`.
- [ ] `layout.scala.html` includes the script *and* the three placeholder ids.
- [ ] Manual browser recipe above passes in Chrome + Firefox.
- [ ] Existing chat/posts demos behave identically (no regression — pure
  additive plumbing).

### Rollback plan

The change is additive across five files. Either:

1. `git revert <sha>` cleanly removes everything (no callers depend on it
   yet — that's the whole point of staging plumbing in its own PR).
2. If only the JS half is wrong: leave the Scala helpers; revert the layout
   include and `turbo-power.js`. Helpers still produce valid `<turbo-stream>`
   wire format that just becomes inert until the JS is re-shipped.

---

## PR #3 — Chat demo wires `notification`, `add_css_class`, `set_attribute`

Now we make the new wire actions visible. Three small additions to
`ChatRoutes`. The post-message HTTP response now returns *multiple* stream
fragments concatenated via `TurboStream.streams`, and a tick-driven
`remove_css_class` is published 2s after each new message.

### Files to edit

- `src/main/scala/hotwire/ChatRoutes.scala`
- `src/main/twirl/views/chat.scala.html` (give the submit button an id)
- `src/test/scala/hotwire/ChatRoutesSpec.scala` (assert the new fragments
  appear in the response body)

### Concrete snippets

**`src/main/twirl/views/chat.scala.html`** — give the submit button a stable
id so `set_attribute` can target it:

```diff
-    <button type="submit">Send</button>
+    <button type="submit" id="chat-submit">Send</button>
```

**`src/main/scala/hotwire/ChatRoutes.scala`** — the patches below are inside
the existing `path("messages")` POST handler. We:

1. Keep the existing `append` fragment.
2. Add an `add_css_class` fragment for the bus only (highlight in *other*
   tabs; the submitter's own tab also gets it via the bus subscribe).
3. Schedule a `remove_css_class` 2s later via `system.scheduler`.
4. Detect a "join" (first message from this author in this room) and broadcast
   a `notification` toast.
5. Return a `set_attribute` fragment in the synchronous response that flips
   `disabled` off on `#chat-submit` (paired with a client-side
   disable-on-submit; see template note below).

Add an `import` and capture the `ActorSystem` so we can use `scheduler` /
`executionContext`:

```diff
 import org.apache.pekko.NotUsed
+import org.apache.pekko.actor.typed.ActorSystem
 import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
 import org.apache.pekko.http.scaladsl.server.Directives.*
 import org.apache.pekko.http.scaladsl.server.Route
 import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}

 import java.time.Instant
 import java.time.format.DateTimeFormatter
 import java.util.UUID
 import scala.collection.concurrent.TrieMap
+import scala.concurrent.duration.*
```

```diff
-final class ChatRoutes(bus: BroadcastBus):
+final class ChatRoutes(bus: BroadcastBus)(using system: ActorSystem[?]):
   import ChatRoutes.*

   // Demo storage. In a real app this is your DB.
   private val rooms = TrieMap.empty[String, Vector[ChatMessage]]
+  // First-seen author tracking, for the "joined the room" toast.
+  private val seenAuthors = TrieMap.empty[String, Set[String]]
```

Replace the body of the `formField("body") { body => … }` block with:

```scala
formField("body") { body =>
  val cleanAuthor = sanitise(author).take(40)
  val msg = ChatMessage(
    id = UUID.randomUUID().toString.take(8),
    author = cleanAuthor,
    body = sanitise(body).take(500),
    timestamp = Instant.now()
  )
  append(room, msg)

  // Track join for the notification toast.
  val joined = seenAuthors.synchronized {
    val before = seenAuthors.getOrElse(room, Set.empty)
    val first  = !before.contains(cleanAuthor)
    if first then seenAuthors.update(room, before + cleanAuthor)
    first
  }

  val appendFrag    = TurboStream.stream(
    TurboStream.Action.Append, "messages", views.html._message(msg))
  val highlightFrag = TurboStream.addCssClass(s"message-${msg.id}", "flash")
  val joinToast: Option[String] =
    Option.when(joined) {
      TurboStream.notification(
        "toasts",
        Html(s"""<div class="toast">${escapeHtml(cleanAuthor)} joined the room</div>""")
      )
    }

  // Broadcast to every tab (including the submitter — Turbo dedupes the
  // append by id, and the highlight class-add is idempotent).
  val broadcast =
    TurboStream.streams((Seq(appendFrag, highlightFrag) ++ joinToast)*)
  bus.publish(topicFor(room), broadcast)

  // Schedule the un-highlight 2s later. Goes only on the bus — submitters
  // and observers all receive it through their WS subscription.
  import system.executionContext
  system.scheduler.scheduleOnce(
    2.seconds,
    () => bus.publish(
      topicFor(room),
      TurboStream.removeCssClass(s"message-${msg.id}", "flash")
    )
  )

  // Synchronous response for the submitter: append + class-add (so the
  // first paint matches what other tabs see) + re-enable submit button.
  val reEnable = TurboStream.removeAttribute("chat-submit", "disabled")
  import TurboStream.given
  complete(TurboStream.streams(appendFrag, highlightFrag, reEnable))
}
```

Add a small HTML escape helper (for the toast author name — `_message.scala.html`
already uses Twirl's auto-escape for messages, but the toast we render here is
hand-built):

```scala
private def escapeHtml(s: String): String =
  s.replace("&", "&amp;")
   .replace("<", "&lt;")
   .replace(">", "&gt;")
   .replace("\"", "&quot;")
```

**`src/main/scala/hotwire/Main.scala`** — `ChatRoutes` now needs `system` as
a context bound. The call site is in `Main`. No change needed —
`given system: ActorSystem[Nothing]` is already in scope at that line; the
new `(using system: ActorSystem[?])` clause picks it up.

**`src/test/scala/hotwire/ChatRoutesSpec.scala`** — extend the existing
"appends" test:

```diff
     assert(body.contains("""action="append""""), body)
     assert(body.contains("""target="messages""""), body)
     assert(body.contains("<template>"), body)
+    assert(body.contains("""action="add_css_class""""), body)
+    assert(body.contains("""classes="flash""""), body)
+    assert(body.contains("""action="remove_attribute""""), body)
+    assert(body.contains("""target="chat-submit""""), body)
```

Add a new test asserting the join-toast logic:

```scala
test("first message from a new author triggers a join notification on the bus") {
  val bus     = new InProcessBroadcastBus()
  val handler = Route.toFunction(new ChatRoutes(bus).routes)

  // Subscribe before posting — InProcessBroadcastBus only delivers
  // post-subscribe messages.
  given ec: scala.concurrent.ExecutionContext = system.executionContext
  val collected = bus.subscribe("chat:lobby")
    .take(1) // append+highlight+notification arrive in a single concatenated frame
    .runFold("")(_ + _)

  val token = "test-token-123"
  val form  = FormData("author" -> "alice", "body" -> "hi", "_csrf" -> token).toEntity
  val req = HttpRequest(HttpMethods.POST, "/chat/lobby/messages", entity = form)
    .addHeader(Cookie("csrf_token" -> token))
  Await.result(handler(req), 5.seconds)

  val frame = Await.result(collected, 5.seconds)
  assert(frame.contains("""action="notification""""), frame)
  assert(frame.contains("alice joined the room"), frame)
}
```

### Validation steps

```
sbt compile
# expect: clean.

sbt test
# expect: ChatRoutesSpec, TurboStreamSpec, PostsRoutesSpec, InProcessBroadcastBusSpec all green.

# Manual two-tab check:
sbt run
#   Tab A: http://localhost:8080/chat/lobby — type "hi" as alice, submit.
#     • A toast "alice joined the room" appears top-right and fades after 3s.
#     • The new message gets a blue outline that disappears after 2s.
#     • The Send button remains usable (disabled flicker on submit, then re-enabled).
#   Tab B: same URL.
#     • Sees the same message appear, same flash, same toast.
#     • Submit a new message as alice — no toast this time (already joined).
#     • Submit as bob — toast for bob.
```

### Done-when

- [ ] `ChatRoutes` takes `(using ActorSystem[?])` and uses it for
  `scheduler.scheduleOnce`.
- [ ] POST response body contains `append` + `add_css_class` +
  `remove_attribute` fragments.
- [ ] First-message-per-author broadcasts a `notification` fragment on the
  bus topic; subsequent messages from the same author do not.
- [ ] After 2s a `remove_css_class` fragment is broadcast.
- [ ] Two-tab manual check passes.
- [ ] All existing tests still pass.

### Rollback plan

If the bus fan-out misbehaves (e.g. NATS implementation can't handle
multi-fragment frames), revert just the `bus.publish(broadcast)` line back to
`bus.publish(fragment)` and drop the scheduled un-highlight. The synchronous
HTTP response stays useful on its own. Worst case, `git revert <sha>` returns
to PR #2's all-additive state.

---

## PR #4 (optional) — Edit-message demo using `morph`

Adds an "edit" affordance per message that demonstrates `morph`'s value over
`replace`: the composer at the bottom keeps its in-progress draft because
morph never blows away the form.

### Files to create / edit

- **edit** `src/main/twirl/views/_message.scala.html` — add an inline edit form
- **edit** `src/main/twirl/views/chat.scala.html` — pass csrf+room to `_message`
- **edit** `src/main/scala/hotwire/ChatRoutes.scala` — add a `POST
  /chat/<room>/messages/<id>/edit` route
- **edit** `src/test/scala/hotwire/ChatRoutesSpec.scala` — assert the edit
  route returns a `morph` stream and updates history

### Concrete snippets

**`src/main/twirl/views/_message.scala.html`** — wrap the existing layout but
add a tiny inline edit form. Keep the same outer `id="message-@msg.id"` so
`morph` targets it:

```html
@(msg: hotwire.ChatRoutes.ChatMessage, csrfToken: String = "", room: String = "")
<div class="message" id="message-@msg.id">
  <strong class="author">@msg.author</strong>
  <span class="body">@msg.body</span>
  <time datetime="@msg.timestamp.toString">@msg.displayTime</time>
  @if(csrfToken.nonEmpty && room.nonEmpty) {
    <form class="edit-form" action="/chat/@room/messages/@msg.id/edit" method="post">
      <input type="hidden" name="_csrf" value="@csrfToken">
      <input type="text" name="body" value="@msg.body" maxlength="500" aria-label="Edit message">
      <button type="submit">Save</button>
    </form>
  }
</div>
```

The default empty-string args keep the original two-arg call sites
(`@views.html._message(msg)` in `chat.scala.html`'s for-loop) compiling. Update
that call site to pass the extras through:

**`src/main/twirl/views/chat.scala.html`**:

```diff
-      @views.html._message(msg)
+      @views.html._message(msg, csrfToken, room)
```

**`src/main/scala/hotwire/ChatRoutes.scala`** — add a sibling route inside the
`pathPrefix("chat" / Segment) { room => concat(...) }` block, alongside
`path("messages")`:

```scala
,
path("messages" / Segment / "edit") { id =>
  post {
    CsrfSupport.withCsrfToken { csrf =>
      CsrfSupport.requireCsrf(csrf) {
        formField("body") { body =>
          val cleaned = sanitise(body).take(500)
          val updated: Option[ChatMessage] = rooms.synchronized {
            val msgs = rooms.getOrElse(room, Vector.empty)
            msgs.indexWhere(_.id == id) match
              case -1 => None
              case i  =>
                val edited = msgs(i).copy(body = cleaned)
                rooms.update(room, msgs.updated(i, edited))
                Some(edited)
          }
          updated match
            case None =>
              complete(StatusCodes.NotFound)
            case Some(edited) =>
              val frag = TurboStream.stream(
                action = TurboStream.Action.Morph,
                target = s"message-$id",
                content = views.html._message(edited, csrf, room)
              )
              bus.publish(topicFor(room), frag)
              import TurboStream.given
              complete(frag)
        }
      }
    }
  }
}
```

**`src/test/scala/hotwire/ChatRoutesSpec.scala`** — append:

```scala
test("POST /chat/<room>/messages/<id>/edit returns a morph turbo-stream") {
  val bus     = new InProcessBroadcastBus()
  val handler = Route.toFunction(new ChatRoutes(bus).routes)
  val token   = "edit-token"

  // Seed a message via the existing POST so we have a real id.
  val seedForm = FormData(
    "author" -> "alice", "body" -> "hi", "_csrf" -> token).toEntity
  val seedReq = HttpRequest(HttpMethods.POST, "/chat/lobby/messages",
    entity = seedForm).addHeader(Cookie("csrf_token" -> token))
  val seedResp = Await.result(handler(seedReq), 5.seconds)

  given ec: scala.concurrent.ExecutionContext = system.executionContext
  val seedBody = Await.result(
    seedResp.entity.toStrict(5.seconds).map(_.data.utf8String), 5.seconds)
  val id = """id="message-([a-f0-9]+)"""".r
    .findFirstMatchIn(seedBody).get.group(1)

  val editForm = FormData("body" -> "edited", "_csrf" -> token).toEntity
  val editReq  = HttpRequest(
    HttpMethods.POST, s"/chat/lobby/messages/$id/edit", entity = editForm
  ).addHeader(Cookie("csrf_token" -> token))
  val editResp = Await.result(handler(editReq), 5.seconds)

  assertEquals(editResp.status, StatusCodes.OK)
  val editBody = Await.result(
    editResp.entity.toStrict(5.seconds).map(_.data.utf8String), 5.seconds)
  assert(editBody.contains("""action="morph""""), editBody)
  assert(editBody.contains(s"""target="message-$id""""), editBody)
  assert(editBody.contains("edited"), editBody)
}
```

### Validation steps

```
sbt compile
sbt test                      # expect all green
sbt run

# Two-tab manual check (the whole point of this PR):
#   Tab A: http://localhost:8080/chat/lobby
#     • Submit a message as alice ("hello").
#     • Begin typing "draft text..." into the bottom composer. DO NOT submit.
#   Tab B: same URL.
#     • Click on alice's message's edit form, change "hello" to "HOLA",
#       click Save.
#   Back on Tab A:
#     • alice's message text becomes "HOLA".
#     • The bottom composer's "draft text..." is STILL THERE.
#       (With Action.Replace it would have been wiped on every fan-out.)
```

### Done-when

- [ ] `_message.scala.html` accepts optional `csrfToken` + `room` and renders
  an edit form when both are non-empty.
- [ ] New route `POST /chat/<room>/messages/<id>/edit` returns a `morph`
  turbo-stream and broadcasts the same fragment.
- [ ] `ChatRoutesSpec` test extracts an id from a seeded message and round-
  trips the edit.
- [ ] Two-tab manual check confirms composer draft survives a remote edit.

### Rollback plan

PR #4 is fully isolated — it adds a route, a template optional arg, one test.
`git revert <sha>` removes everything; PRs #1–#3 remain useful. If the morph
behaviour ever breaks on a future Turbo upgrade, falling back to
`Action.Replace` is a one-token change in this route while we investigate.

---

## Rollout summary

| PR | Scope | LOC | Risk |
| -- | ----- | --- | ---- |
| #1 | `Action.Morph` enum case + 1 test | ~10 | None — no callers |
| #2 | `streamAttrs` + 6 helpers + `turbo-power.js` + layout | ~150 | Low — additive |
| #3 | Chat demo wires notification/css-class/set-attribute | ~60 | Medium — touches hot path |
| #4 | Optional edit-via-morph demo | ~80 | Low — new route only |

Each PR is independently reviewable, independently revertable, and adds zero
runtime dependencies. No JS framework, no JSON dependency, no build step.
