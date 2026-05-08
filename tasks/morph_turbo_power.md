# Plan: `morph` action + turbo_power-style server-driven actions

Two related additions to `TurboStream.scala` plus one demo per feature. Both broaden
the *wire surface area* the server can drive without adding any client-side code
beyond what Turbo 8 already ships.

References:
- Turbo 8 morph: <https://turbo.hotwired.dev/handbook/page_refreshes>
- `turbo-morph` (Rails port that introduced the action): <https://github.com/hopsoft/turbo-morph>
- `turbo_power` (extra stream actions): <https://github.com/marcoroth/turbo_power-rails>

## Part 1 — `morph` stream action

### Why

The four "core" actions (`append`, `prepend`, `replace`, `update`) all *blow away*
the target subtree. That nukes form state, focus, scroll position, and any
client-side mutation a Stimulus controller put there. `morph` is an idempotent
patch driven by [idiomorph](https://github.com/bigskysoftware/idiomorph) (bundled
in Turbo 8): it diffs the new HTML against the live DOM and only changes the
nodes/attributes that actually differ.

Wire format is identical to other stream actions — only the `action` attribute
changes:

```html
<turbo-stream action="morph" target="post-42">
  <template>…the new HTML for #post-42…</template>
</turbo-stream>
```

There is also `action="refresh"` (full-page morph) but that's a layout-level
thing; this plan covers per-target morph only.

### Code changes

1. **`src/main/scala/hotwire/TurboStream.scala`**
   - Add `Morph` to the `Action` enum (`extends Action("morph")`).
   - No changes to `stream()` needed — the existing `_ =>` branch already wraps
     with `<template>`, which is what `morph` expects.
   - Add `Refresh` while we're there? **No** — it needs a different wrapper
     (no target/template). Defer to a follow-up if requested.

2. **`src/test/scala/hotwire/TurboStreamSpec.scala`**
   - Mirror the existing `Action.Replace` test for `Action.Morph`. One assertion
     on the rendered string is enough.

### Demo

Pick the existing chat demo as the host — it already has form state worth
preserving:

- Add an "edit" affordance on each message: a small `<form>` that POSTs
  `/chat/<room>/messages/<id>` with a new body.
- Server appends the corrected message (renders `_message.scala.html` with the
  same id) and broadcasts a **morph** stream targeting `message-<id>` instead
  of an append.
- Acceptance test (manual): in tab A, start typing a *new* message in the
  composer at the bottom. In tab B, edit an old message. Tab A's composer
  draft must not be cleared — that's the value over `replace`.

Optional, smaller demo: add a "✓ seen" badge that ticks every 5s on the most
recent message via a morph. Demonstrates idempotence — repeated morph with the
same HTML is a no-op (no diff, no DOM mutation).

### Out of scope

- `data-turbo-permanent`. Already a no-op for morph since morph preserves form
  state by default; mention in README, no code change.
- `<turbo-frame refresh="morph">` and `<meta name="turbo-refresh-method"
  content="morph">`. Those are page-level flags, not part of the stream action
  surface.

---

## Part 2 — `turbo_power`-style server-driven actions

### Why

Turbo's spec has 7 actions. `turbo_power` ships ~25 more by treating the
`<turbo-stream>` element as a generic *server-to-client RPC* envelope. Custom
actions are registered on the client with `Turbo.StreamActions.foo = function()
{ … }` and then any `<turbo-stream action="foo">` triggers that function.

The high-leverage additions (and what each enables in this codebase):

| Action            | Effect                                              | Demo it unlocks                          |
| ----------------- | --------------------------------------------------- | ---------------------------------------- |
| `redirect`        | `Turbo.visit(href)`                                 | Server-pushed nav after long job        |
| `notification`    | Append a toast into a fixed flash region            | Cross-tab "Bob joined the room"         |
| `set_attribute`   | `el.setAttribute(name, value)`                      | Disable a button while job runs         |
| `remove_attribute`| `el.removeAttribute(name)`                          | Re-enable it                            |
| `add_css_class`   | `el.classList.add(value)`                           | Highlight new message                   |
| `remove_css_class`| `el.classList.remove(value)`                        | Un-highlight after 2s                   |
| `dispatch_event`  | `el.dispatchEvent(new CustomEvent(name, …))`        | Hook for any Stimulus controller        |

Wire format conventions used by `turbo_power`:

```html
<!-- attribute-only actions: no <template>; data passed as attrs -->
<turbo-stream action="set_attribute" target="submit-btn"
              attribute="disabled" value="true"></turbo-stream>

<turbo-stream action="add_css_class" target="message-7"
              classes="flash"></turbo-stream>

<turbo-stream action="redirect" target="body" url="/posts"></turbo-stream>

<turbo-stream action="dispatch_event" target="window"
              name="job:done" detail='{"id":42}'></turbo-stream>

<!-- content actions: <template> as usual -->
<turbo-stream action="notification" target="toasts">
  <template><div class="toast">Saved.</div></template>
</turbo-stream>
```

`target="window"` and `target="body"` are conventional for "no specific
element" — `set_attribute`/`dispatch_event`/`redirect` use these.

### Code changes

1. **`src/main/scala/hotwire/TurboStream.scala`** — extend the API:

   ```scala
   enum Action(val name: String):
     case Append, Prepend, Replace, Update, Remove, Before, After, Morph
     // existing wire-format-stable actions

     // turbo_power custom actions (require client-side registration)
     case Redirect       extends Action("redirect")
     case Notification   extends Action("notification")
     case SetAttribute   extends Action("set_attribute")
     case RemoveAttribute extends Action("remove_attribute")
     case AddCssClass    extends Action("add_css_class")
     case RemoveCssClass extends Action("remove_css_class")
     case DispatchEvent  extends Action("dispatch_event")
   ```

   The existing single `stream(action, target, content)` signature only covers
   *content* actions. Add overloads for attribute-only ones — keep the existing
   one untouched so current call sites compile:

   ```scala
   def streamAttrs(action: Action, target: String, attrs: (String, String)*): String =
     val rendered = attrs.map((k, v) => s"""$k="${escapeAttr(v)}"""").mkString(" ")
     s"""<turbo-stream action="${action.name}" target="${escapeAttr(target)}" $rendered></turbo-stream>"""
   ```

   And convenience helpers (each one a single-line wrapper around
   `streamAttrs` / `stream`):

   ```scala
   def redirect(url: String): String =
     streamAttrs(Action.Redirect, "body", "url" -> url)

   def notification(target: String, content: Html): String =
     stream(Action.Notification, target, content)

   def setAttribute(target: String, name: String, value: String): String =
     streamAttrs(Action.SetAttribute, target, "attribute" -> name, "value" -> value)

   def addCssClass(target: String, classes: String): String =
     streamAttrs(Action.AddCssClass, target, "classes" -> classes)

   def dispatchEvent(target: String, name: String, detail: String = ""): String =
     val base = Seq("name" -> name)
     val all = if detail.isEmpty then base else base :+ ("detail" -> detail)
     streamAttrs(Action.DispatchEvent, target, all*)
   ```

2. **Client-side registration.** Custom actions need a one-time JS install.
   Add `src/main/resources/public/turbo-power.js` (~40 lines of vanilla JS,
   no build step), wired in via `<script>` in `layout.scala.html`. Keep it
   tiny and inline-readable; don't pull `npm` into this repo.

   ```js
   // src/main/resources/public/turbo-power.js
   Turbo.StreamActions.redirect = function () {
     Turbo.visit(this.getAttribute("url"))
   }
   Turbo.StreamActions.set_attribute = function () {
     for (const el of this.targetElements)
       el.setAttribute(this.getAttribute("attribute"), this.getAttribute("value"))
   }
   Turbo.StreamActions.remove_attribute = function () { /* … */ }
   Turbo.StreamActions.add_css_class = function () {
     const classes = (this.getAttribute("classes") || "").split(/\s+/).filter(Boolean)
     for (const el of this.targetElements) el.classList.add(...classes)
   }
   Turbo.StreamActions.remove_css_class = function () { /* … */ }
   Turbo.StreamActions.notification = function () {
     // append <template> contents into target — same shape as <turbo-stream action="append">
     for (const el of this.targetElements)
       el.append(this.templateContent.cloneNode(true))
   }
   Turbo.StreamActions.dispatch_event = function () {
     const name = this.getAttribute("name")
     const detail = this.hasAttribute("detail")
       ? JSON.parse(this.getAttribute("detail"))
       : null
     for (const el of this.targetElements)
       el.dispatchEvent(new CustomEvent(name, { detail, bubbles: true }))
   }
   ```

   Special-case targets: when `target="window"`, `targetElements` returns
   `[window]`; when `target="body"`, `[document.body]`. Handled in the
   helper:

   ```js
   Object.defineProperty(StreamElement.prototype, "targetElements", {
     get() {
       const t = this.getAttribute("target")
       if (t === "window") return [window]
       if (t === "body") return [document.body]
       return Array.from(document.querySelectorAll("#" + CSS.escape(t)))
     }
   })
   ```

   (Or skip the override and require demos to use a real id like `#flash`.
   Simpler — recommended for v1.)

3. **`src/main/twirl/views/layout.scala.html`**
   - Add `<script src="/public/turbo-power.js" defer></script>` after the
     Turbo CDN script.
   - Add a permanent toast region: `<div id="toasts" class="toasts"></div>`
     in the layout body. Style in `style.css`.

4. **`src/test/scala/hotwire/TurboStreamSpec.scala`**
   - Add tests for `streamAttrs`, `redirect`, `setAttribute`, `addCssClass`,
     `dispatchEvent` — assert exact rendered strings, including attribute
     escaping.

### Demo

Hook these into the existing chat demo to make the value obvious:

- **Notification** — when a new user types in the room (first message), broadcast
  a `notification` stream targeting `#toasts` saying "Alice joined the room".
  Auto-dismiss the toast after 3s with a `setTimeout` in the JS.
- **add_css_class + remove_css_class** — when a message arrives, append it
  *and* push an `add_css_class action="add_css_class" target="message-<id>"
  classes="flash"`. Two seconds later (`Source.tick`-driven), publish a
  `remove_css_class` to peel it off. Visual highlight without per-message JS.
- **set_attribute** — on the composer form: when the user submits, the
  *response* includes a `set_attribute` stream re-enabling the submit button
  (matching client-side disabled-on-submit pattern).

Each of those is 5–10 lines of Scala in `ChatRoutes.scala`.

### Risk / open questions

- **Action name collisions.** `notification`/`redirect` are not part of the
  Turbo spec, so future Turbo versions adding them with different semantics is
  unlikely but possible. Keep custom-action names in a single registry file
  (`turbo-power.js`) so the blast radius is one file if we need to rename.
- **Cross-tab CSRF for redirect.** A `redirect` stream broadcast on the bus
  would yank *every* tab to the same URL. That's almost never what you want.
  Demo should only emit `redirect` on the *synchronous* HTTP response, not
  through `bus.publish`. Document this in the helper's scaladoc.
- **JSON in `detail` attribute.** The encoding round-trip (JSON → attr →
  `JSON.parse`) means strings need full HTML attribute escaping plus JSON
  escaping. The existing `escapeAttr` handles the former; callers must pass
  already-JSON-encoded strings. Add a `dispatchEvent(target, name, detail:
  JsValue)` overload only if/when we pull in a JSON lib — out of scope here.

---

## Rollout order

1. PR #1: `Morph` enum case + test + scaladoc note. ~10 LOC. Zero new behaviour
   if no caller uses it.
2. PR #2: `streamAttrs` + `Redirect`/`SetAttribute`/`AddCssClass`/
   `RemoveCssClass`/`DispatchEvent`/`Notification` enum cases + tests +
   `turbo-power.js` + layout script include + `#toasts` region. No demo wiring
   yet — pure plumbing.
3. PR #3: Chat demo wiring — flash highlight on new message (cssClass round
   trip), join-toast (notification), composer re-enable (setAttribute).
4. PR #4 (optional): Edit-message flow using `morph` to demonstrate state
   preservation vs `replace`.

Each PR is independently reviewable and reversible.
