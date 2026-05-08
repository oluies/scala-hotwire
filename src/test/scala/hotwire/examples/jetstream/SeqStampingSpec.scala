package hotwire.examples.jetstream

import hotwire.TurboStream
import hotwire.examples.jetstream.ReplayChatRoutes.SeqStamping
import munit.FunSuite
import play.twirl.api.Html

class SeqStampingSpec extends FunSuite:

  test("stamps data-seq onto a turbo-stream fragment") {
    val fragment = TurboStream.stream(
      action = TurboStream.Action.Append,
      target = "messages",
      content = Html("<p>hi</p>")
    )
    val stamped = SeqStamping.stamp(fragment, 42L)
    assert(stamped.startsWith("""<turbo-stream data-seq="42" """), stamped)
    // Original attributes survive unchanged.
    assert(stamped.contains("""action="append""""), stamped)
    assert(stamped.contains("""target="messages""""), stamped)
    assert(stamped.contains("<template><p>hi</p></template>"), stamped)
  }

  test("stamps a remove action that has no template body") {
    val fragment = TurboStream.stream(
      action = TurboStream.Action.Remove,
      target = "msg-1",
      content = Html("ignored")
    )
    val stamped = SeqStamping.stamp(fragment, 7L)
    assert(stamped.startsWith("""<turbo-stream data-seq="7" """), stamped)
    assert(stamped.contains("""action="remove""""), stamped)
  }

  test("idempotent — already-stamped fragments are not double-stamped") {
    val fragment = TurboStream.stream(
      action = TurboStream.Action.Append,
      target = "messages",
      content = Html("hi")
    )
    val once  = SeqStamping.stamp(fragment, 1L)
    val twice = SeqStamping.stamp(once, 99L)
    assertEquals(once, twice)
  }

  test("non-turbo-stream input is passed through unchanged") {
    val notATurboStream = "<div>just html</div>"
    assertEquals(SeqStamping.stamp(notATurboStream, 5L), notATurboStream)
  }

  test("template body containing 'data-seq=' does not block stamping") {
    // Twirl HTML-escapes <, >, &, and quotes but not '=' or alphanumerics, so a
    // user message body like "Hi data-seq= world" reaches the wrapper as-is.
    val fragment = TurboStream.stream(
      action  = TurboStream.Action.Append,
      target  = "messages",
      content = Html("""<span class="body">Hi data-seq= world</span>""")
    )
    val stamped = SeqStamping.stamp(fragment, 13L)
    assert(stamped.startsWith("""<turbo-stream data-seq="13" """), stamped)
    // The user-content occurrence inside the template survives untouched.
    assert(stamped.contains("Hi data-seq= world"), stamped)
  }
