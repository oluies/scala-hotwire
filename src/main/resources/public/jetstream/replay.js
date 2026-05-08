// Custom <replaying-turbo-stream-source> element for the JetStream demo.
//
// Differs from Turbo's built-in <turbo-stream-source> in two ways:
//   1. Tracks the highest `data-seq` it has rendered and stores it in
//      sessionStorage. On reconnect (network blip, sleep/wake) it appends
//      `?last_seq=N` to the WebSocket URL so the server replays only what was
//      missed via a JetStream consumer with DeliverPolicy.ByStartSequence.
//   2. Has its own reconnect loop with exponential backoff. Turbo's built-in
//      source reconnects to the same URL, which would re-deliver from "live
//      tail" and silently drop any messages that landed during the disconnect.
//
// Why sessionStorage and not localStorage: localStorage is per-origin and is
// shared across all tabs. Two tabs on the same room would corrupt each other's
// progress marker — a tab that already rendered up to seq=100 would write 100
// even for a backgrounded sibling that has only rendered up to seq=49, and on
// reconnect the sibling would request last_seq=100 and silently lose 50..100.
// sessionStorage is per-tab (persists across tab reload but not across tabs) —
// exactly the semantic we want for "highest seq THIS tab has rendered".
//
// Why this script is loaded as `type="module"`: ES module scripts have implicit
// defer and execute in document order *after* the parser finishes. Turbo is
// loaded as a module too, so by the time this file runs `window.Turbo` is
// defined. A classic <script src> would race the deferred Turbo module and
// could throw `ReferenceError: Turbo is not defined` on the first frame, after
// already advancing sessionStorage — silently losing the message.
//
// The element reads two data attributes:
//   data-src   WebSocket URL (without query string)
//   data-room  room name, used as the sessionStorage key
class ReplayingTurboStreamSource extends HTMLElement {
  connectedCallback() {
    this.url    = this.dataset.src;
    this.room   = this.dataset.room;
    this.seqKey = `lastSeq:${this.room}`;

    this.shouldReconnect = true;
    this.backoffMs       = 250;
    this.#connect();
  }

  disconnectedCallback() {
    this.shouldReconnect = false;
    if (this.ws) {
      try { this.ws.close(); } catch (_) {}
    }
  }

  #connect() {
    // Always send last_seq, even when 0. The server treats Some(0) as "from
    // the start of the retention window" and Some(N>0) as "strictly after N",
    // so a fresh tab (last=0) backfills the entire retention window via the
    // WebSocket while a reconnecting tab backfills only what it missed.
    const last = this.#getSeq();
    const url  = `${this.url}?last_seq=${last}`;

    let ws;
    try { ws = new WebSocket(url); }
    catch (e) { this.#scheduleReconnect(); return; }
    this.ws = ws;

    ws.addEventListener("open", () => { this.backoffMs = 250; });

    ws.addEventListener("message", (e) => {
      if (typeof e.data !== "string") return;

      // Hand the fragment to Turbo *before* advancing sessionStorage. If the
      // call throws (e.g. malformed payload, unrelated bug in a future Turbo)
      // we must not advance the seq — otherwise the next reconnect would skip
      // this message via DeliverPolicy.ByStartSequence and lose it forever.
      try {
        Turbo.renderStreamMessage(e.data);
      } catch (err) {
        console.error("Turbo.renderStreamMessage failed; not advancing seq", err);
        return;
      }

      const seqs = [...e.data.matchAll(/data-seq="(\d+)"/g)].map(m => parseInt(m[1], 10));
      if (seqs.length) {
        const max = Math.max(...seqs);
        if (max > this.#getSeq()) this.#setSeq(max);
      }
    });

    ws.addEventListener("close", () => {
      if (this.shouldReconnect) this.#scheduleReconnect();
    });

    ws.addEventListener("error", () => {
      try { ws.close(); } catch (_) {}
    });
  }

  #scheduleReconnect() {
    const delay = this.backoffMs;
    this.backoffMs = Math.min(this.backoffMs * 2, 10_000);
    setTimeout(() => { if (this.shouldReconnect) this.#connect(); }, delay);
  }

  #getSeq() {
    return parseInt(sessionStorage.getItem(this.seqKey) || "0", 10) || 0;
  }

  #setSeq(n) {
    sessionStorage.setItem(this.seqKey, String(n));
  }
}

customElements.define("replaying-turbo-stream-source", ReplayingTurboStreamSource);
