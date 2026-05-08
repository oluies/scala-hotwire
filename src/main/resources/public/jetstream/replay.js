// Custom <replaying-turbo-stream-source> element for the JetStream demo.
//
// Differs from Turbo's built-in <turbo-stream-source> in two ways:
//   1. Tracks the highest `data-seq` it has ever rendered and stores it in
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
// The element reads three data attributes:
//   data-src         WebSocket URL (without query string)
//   data-room        room name, used as the sessionStorage key
//   data-initial-seq highest seq the server already has at page render time
class ReplayingTurboStreamSource extends HTMLElement {
  connectedCallback() {
    this.url    = this.dataset.src;
    this.room   = this.dataset.room;
    this.seqKey = `lastSeq:${this.room}`;

    const initial = parseInt(this.dataset.initialSeq || "0", 10);
    if (initial > this.#getSeq()) this.#setSeq(initial);

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
    const last = this.#getSeq();
    const url  = last > 0 ? `${this.url}?last_seq=${last}` : this.url;

    let ws;
    try { ws = new WebSocket(url); }
    catch (e) { this.#scheduleReconnect(); return; }
    this.ws = ws;

    ws.addEventListener("open", () => { this.backoffMs = 250; });

    ws.addEventListener("message", (e) => {
      if (typeof e.data !== "string") return;
      // Track highest seq before handing the fragment to Turbo, so the
      // sessionStorage value reflects what the *server sent* even if Turbo's
      // before-stream-render hook never fires (e.g. duplicate id removed).
      const seqs = [...e.data.matchAll(/data-seq="(\d+)"/g)].map(m => parseInt(m[1], 10));
      if (seqs.length) {
        const max = Math.max(...seqs);
        if (max > this.#getSeq()) this.#setSeq(max);
      }
      Turbo.renderStreamMessage(e.data);
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
