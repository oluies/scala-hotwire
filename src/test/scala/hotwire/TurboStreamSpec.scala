package hotwire

import munit.FunSuite
import play.twirl.api.Html

class TurboStreamSpec extends FunSuite:

  test("append wraps content in a turbo-stream/template element") {
    val s = TurboStream.stream(
      action = TurboStream.Action.Append,
      target = "messages",
      content = Html("<div>hi</div>")
    )
    assert(s.contains("""action="append""""), s)
    assert(s.contains("""target="messages""""), s)
    assert(s.contains("<template><div>hi</div></template>"), s)
  }

  test("remove omits the template payload") {
    val s = TurboStream.stream(
      action = TurboStream.Action.Remove,
      target = "msg-1",
      content = Html("ignored")
    )
    assert(!s.contains("<template>"), s)
    assert(s.contains("""action="remove""""), s)
    assert(s.contains("""target="msg-1""""), s)
  }

  test("target attribute is HTML-attribute-escaped") {
    val s = TurboStream.stream(
      action = TurboStream.Action.Append,
      target = """foo"><script>alert(1)</script>""",
      content = Html("x")
    )
    assert(!s.contains("<script>"), s)
    assert(s.contains("&quot;"), s)
  }

  test("media type is text/vnd.turbo-stream.html with utf-8 charset") {
    assertEquals(TurboStream.mediaType.mainType, "text")
    assertEquals(TurboStream.mediaType.subType, "vnd.turbo-stream.html")
    assertEquals(TurboStream.contentType.charset.value, "UTF-8")
  }

  test("streams() concatenates fragments") {
    val a = TurboStream.stream(TurboStream.Action.Append, "x", Html("1"))
    val b = TurboStream.stream(TurboStream.Action.Append, "x", Html("2"))
    assertEquals(TurboStream.streams(a, b), a + b)
  }
