package hotwire

import hotwire.TwirlSupport.given
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Demo feed demonstrating Hotwire infinite-scroll pagination via `<turbo-frame
  * loading="lazy">`. No WebSocket, no broadcast bus — pagination is one-directional
  * and ephemeral, so a `<turbo-stream-source>` would be overkill. (See the dev.to
  * "Hotwire's dark side" post: real-time isn't free; pick the simplest mechanism that
  * does the job.)
  *
  * Wire pattern (per the Cycode "Infinite Scrolling Pagination with Hotwire" post):
  *
  *   1. `GET /posts` returns a full page with the first batch of items inline, plus a
  *      placeholder `<turbo-frame id="posts-page-2" loading="lazy" src="/posts/page/2">`
  *      at the bottom.
  *
  *   2. When the placeholder scrolls into the viewport, Turbo fetches its `src` and
  *      replaces the frame's contents with the response body, which is itself a
  *      `<turbo-frame id="posts-page-2">` containing the next batch and a *new* lazy
  *      placeholder for `posts-page-3`. Repeat until the last page returns no further
  *      placeholder.
  *
  * Replacement is by id, not by element: Turbo finds the matching `<turbo-frame
  * id="posts-page-2">` in the response and copies *its children* over the existing
  * frame's children. The frame element itself stays in the DOM. New nested lazy
  * frames within those children are what drive the next fetch.
  */
final class PostsRoutes(perPage: Int = 10, total: Int = 87):
  import PostsRoutes.*

  private val all: Vector[Post] = seed(total)
  private val totalPages: Int =
    if all.isEmpty then 1 else math.ceil(all.size.toDouble / perPage).toInt

  val routes: Route =
    pathPrefix("posts") {
      concat(
        pathEndOrSingleSlash {
          get {
            complete(views.html.posts(pageSlice(1), 1, totalPages))
          }
        },
        path("page" / IntNumber) { page =>
          get {
            val clamped = page.max(1).min(totalPages)
            complete(views.html._posts_page(pageSlice(clamped), clamped, totalPages))
          }
        }
      )
    }

  private def pageSlice(p: Int): Vector[Post] =
    all.slice((p - 1) * perPage, p * perPage)

object PostsRoutes:
  final case class Post(
      id: Int,
      title: String,
      author: String,
      snippet: String,
      timestamp: Instant
  ):
    def displayTime: String =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
        .format(timestamp)

  private val authors = Vector("ada", "linus", "grace", "donald", "barbara", "ken", "rich")
  private val topics  = Vector(
    "on streams as the unit of composition",
    "why JSON envelopes are the worst part of your stack",
    "back-pressure considered useful",
    "the case against the SPA",
    "HTML over the wire, ten years later",
    "merge hubs and broadcast hubs, illustrated",
    "what NATS taught me about subjects"
  )

  /** Deterministic synthetic data so tests can assert on exact slices. */
  def seed(n: Int): Vector[Post] =
    val now = Instant.now()
    (1 to n).toVector.map { i =>
      val a = authors((i * 31) % authors.size)
      val t = topics((i * 17) % topics.size)
      Post(
        id = i,
        title = s"#$i — $t",
        author = a,
        snippet =
          s"Post number $i in the demo feed. Scroll past it and the next " +
          s"turbo-frame placeholder will lazy-load the following page.",
        timestamp = now.minus(i.toLong, ChronoUnit.MINUTES)
      )
    }
