package hotwire

import org.apache.pekko.http.scaladsl.model.headers.{`Set-Cookie`, HttpCookie, SameSite}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.{AuthorizationFailedRejection, Directive0, Directive1}

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

/** Minimal double-submit-cookie CSRF protection.
  *
  *   - On any GET, [[withCsrfToken]] guarantees a `csrf_token` cookie exists and exposes its value.
  *     Templates render the value into a hidden form field and a `<meta name="csrf-token">` tag.
  *   - On mutating requests, [[requireCsrf]] insists the request carry the same token in either
  *     the `_csrf` form field or the `X-CSRF-Token` header. Mismatch → 403 (rejection).
  *
  * The cookie is not signed because the only thing it protects against is cross-site
  * forgery — an attacker who can read the user's cookies has already won at a different layer.
  * It is set `SameSite=Lax`, which on its own already blocks most cross-origin POSTs in
  * modern browsers; the double-submit check is the belt-and-braces.
  *
  * For per-user *authenticated* sessions you'd add a separate signed cookie; this demo doesn't
  * model authentication.
  */
object CsrfSupport:

  private val cookieName = "csrf_token"
  private val rng        = new SecureRandom()
  private val b64        = Base64.getUrlEncoder.withoutPadding

  private def randomToken(bytes: Int = 24): String =
    val arr = new Array[Byte](bytes)
    rng.nextBytes(arr)
    b64.encodeToString(arr)

  private def constantTimeEquals(a: String, b: String): Boolean =
    val ab = a.getBytes("UTF-8")
    val bb = b.getBytes("UTF-8")
    ab.length == bb.length && MessageDigest.isEqual(ab, bb)

  /** Provide the current CSRF token, minting a new cookie if the request didn't carry one. */
  def withCsrfToken: Directive1[String] =
    optionalCookie(cookieName).flatMap {
      case Some(c) => provide(c.value)
      case None =>
        val token = randomToken()
        val cookie = HttpCookie(cookieName, token)
          .withPath("/")
          .withSameSite(SameSite.Lax)
        mapResponse(_.addHeader(`Set-Cookie`(cookie))) & provide(token)
    }

  /** Reject the request unless it carries `expected` in `X-CSRF-Token` or `_csrf` form field. */
  def requireCsrf(expected: String): Directive0 =
    optionalHeaderValueByName("X-CSRF-Token").flatMap {
      case Some(t) if constantTimeEquals(t, expected) => pass
      case _ =>
        formFieldMap.flatMap { fields =>
          fields.get("_csrf") match
            case Some(t) if constantTimeEquals(t, expected) => pass
            case _                                          => reject(AuthorizationFailedRejection)
        }
    }
