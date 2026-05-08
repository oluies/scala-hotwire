package hotwire

import org.apache.pekko.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity}
import play.twirl.api.{Html, Txt, Xml}

/** Default `text/html`, `text/plain`, `text/xml` marshallers for Twirl outputs.
  *
  * Pekko HTTP doesn't know about `play.twirl.api.Html` out of the box, so a route that
  * does `complete(views.html.foo())` won't compile without an in-scope
  * `ToEntityMarshaller[Html]`. This provides one per Twirl output type.
  *
  * Turbo Stream responses are a separate marshaller in [[TurboStream]] keyed to
  * `text/vnd.turbo-stream.html`. When both are in scope, Pekko HTTP picks based on
  * the request's `Accept` header — exactly what Turbo's content negotiation expects.
  */
object TwirlSupport:

  given htmlMarshaller: ToEntityMarshaller[Html] =
    Marshaller.withFixedContentType(ContentTypes.`text/html(UTF-8)`): html =>
      HttpEntity(ContentTypes.`text/html(UTF-8)`, html.body)

  given txtMarshaller: ToEntityMarshaller[Txt] =
    Marshaller.withFixedContentType(ContentTypes.`text/plain(UTF-8)`): txt =>
      HttpEntity(ContentTypes.`text/plain(UTF-8)`, txt.body)

  given xmlMarshaller: ToEntityMarshaller[Xml] =
    Marshaller.withFixedContentType(ContentTypes.`text/xml(UTF-8)`): xml =>
      HttpEntity(ContentTypes.`text/xml(UTF-8)`, xml.body)
