package hotwire

import org.apache.pekko.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import org.apache.pekko.http.scaladsl.model.{ContentType, HttpCharsets, HttpEntity, MediaType}
import play.twirl.api.Html

/** Turbo Streams plumbing for Pekko HTTP.
  *
  * Wire protocol per Turbo source ([[https://github.com/hotwired/turbo/blob/main/src/core/streams/stream_message.js]]):
  *   - HTTP form responses: `Content-Type: text/vnd.turbo-stream.html; charset=utf-8`,
  *     body is one or more `<turbo-stream action="..." target="...">` elements.
  *   - WebSocket frames: a UTF-8 *text* frame whose payload is the same HTML.
  *     No JSON envelope, no Action Cable framing, no binary.
  */
object TurboStream:

  val mediaType: MediaType.WithFixedCharset =
    MediaType.customWithFixedCharset(
      mainType = "text",
      subType = "vnd.turbo-stream.html",
      charset = HttpCharsets.`UTF-8`
    )

  val contentType: ContentType.WithFixedCharset = ContentType(mediaType)

  given htmlMarshaller: ToEntityMarshaller[Html] =
    Marshaller.withFixedContentType(contentType): html =>
      HttpEntity(contentType, html.body)

  given stringMarshaller: ToEntityMarshaller[String] =
    Marshaller.withFixedContentType(contentType): s =>
      HttpEntity(contentType, s)

  /** Turbo Stream actions documented at https://turbo.hotwired.dev/reference/streams */
  enum Action(val name: String):
    case Append  extends Action("append")
    case Prepend extends Action("prepend")
    case Replace extends Action("replace")
    case Update  extends Action("update")
    case Remove  extends Action("remove")
    case Before  extends Action("before")
    case After   extends Action("after")

  /** Wrap rendered HTML in a `<turbo-stream>` element targeting an element id. */
  def stream(action: Action, target: String, content: Html): String =
    action match
      case Action.Remove =>
        s"""<turbo-stream action="remove" target="${escapeAttr(target)}"></turbo-stream>"""
      case _ =>
        s"""<turbo-stream action="${action.name}" target="${escapeAttr(target)}"><template>${content.body}</template></turbo-stream>"""

  /** Concatenate multiple stream fragments into one response/frame. */
  def streams(parts: String*): String = parts.mkString

  private def escapeAttr(s: String): String =
    s.replace("&", "&amp;")
     .replace("\"", "&quot;")
     .replace("<", "&lt;")
     .replace(">", "&gt;")
