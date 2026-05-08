package hotwire

import com.typesafe.config.ConfigFactory
import hotwire.examples.jetstream.{JetStreamBroadcastBus, ReplayChatRoutes, ReplayableBroadcastBus}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

object Main:
  private val log = LoggerFactory.getLogger(getClass)

  /** Which demo(s) to mount, selected via the `DEMO` env var (or `hotwire.demo`). */
  enum Demo:
    case All, Chat, Posts, Jobs
  object Demo:
    def parse(s: String): Demo = s.trim.toLowerCase match
      case "all" | ""   => All
      case "chat"       => Chat
      case "posts"      => Posts
      case "jobs"       => Jobs
      case other        =>
        sys.error(s"Unknown DEMO=$other (expected one of: all, chat, posts, jobs)")

  def main(args: Array[String]): Unit =
    val cfg     = ConfigFactory.load().getConfig("hotwire")
    val host    = cfg.getString("host")
    val port    = cfg.getInt("port")
    val natsUrl = Try(cfg.getString("nats-url")).toOption.filter(_.nonEmpty)
    val demo    = Demo.parse(Try(cfg.getString("demo")).getOrElse("all"))
    val jsStreamName =
      Try(cfg.getString("nats-js-stream")).toOption.filter(_.nonEmpty)

    given system: ActorSystem[Nothing] =
      ActorSystem(Behaviors.empty, "hotwire")
    import system.executionContext

    // The bus is only needed for the chat demo. Don't connect to NATS or spin up an
    // in-process bus when only the feed demo is mounted.
    val busOpt: Option[BroadcastBus] = demo match
      case Demo.Posts => None
      case _ =>
        natsUrl match
          case Some(url) =>
            log.info(s"Connecting to NATS at $url")
            Some(new NatsBroadcastBus(NatsBroadcastBus.connect(url)))
          case None =>
            log.info("Using in-process broadcast bus (set NATS_URL to use NATS)")
            Some(new InProcessBroadcastBus())

    // Optional JetStream replay example. Activated when chat is mounted *and* both
    // NATS_URL and NATS_JS_STREAM are set; routes mount under /jetstream-chat/<room>
    // alongside the core chat.
    val replayBus: Option[ReplayableBroadcastBus] =
      (demo, natsUrl, jsStreamName) match
        case (Demo.Posts, _, _)              => None
        case (_, Some(url), Some(name)) =>
          log.info(s"Connecting JetStream replay bus at $url (stream=$name)")
          Some(
            new JetStreamBroadcastBus(
              JetStreamBroadcastBus.connectAndEnsureStream(url, streamName = name, subjectsWildcard = "jschat.>"),
              streamName = name
            )
          )
        case _ => None

    val chatRoutes:   Option[Route] =
      Option.when(demo == Demo.All || demo == Demo.Chat)(busOpt).flatten
        .map(b => new ChatRoutes(b).routes)
    val replayRoutes: Option[Route] = replayBus.map(b => new ReplayChatRoutes(b).routes)
    val postsRoutes:  Option[Route] =
      Option.when(demo == Demo.All || demo == Demo.Posts)(new PostsRoutes().routes)
    val jobsRoutes:   Option[Route] =
      Option.when(demo == Demo.All || demo == Demo.Jobs)(busOpt).flatten
        .map(b => new JobsRoutes(b).routes)

    val landing = demo match
      case Demo.Posts => "/posts"
      case Demo.Jobs  => "/jobs"
      case _          => "/chat/lobby"

    val mounted: Seq[Route] =
      chatRoutes.toSeq ++ replayRoutes.toSeq ++ postsRoutes.toSeq ++ jobsRoutes.toSeq ++ Seq(
        pathPrefix("public") { getFromResourceDirectory("public") },
        pathSingleSlash { redirect(landing, StatusCodes.TemporaryRedirect) }
      )
    val routes = concat(mounted*)

    log.info(s"Mounting demo=$demo")

    Http().newServerAt(host, port).bind(routes).onComplete {
      case Success(binding) =>
        val replayHint = if replayBus.isDefined then "  →  /jetstream-chat/lobby for replay demo" else ""
        log.info(s"Listening on http://$host:$port  →  open $landing$replayHint")
        sys.addShutdownHook {
          log.info("Shutting down…")
          val done = for
            _ <- binding.terminate(5.seconds)
            _ <- busOpt.map(_.shutdown()).getOrElse(Future.successful(()))
            _ <- replayBus.map(_.shutdown()).getOrElse(Future.successful(()))
          yield
            system.terminate()
          scala.concurrent.Await.result(done, 10.seconds)
          scala.concurrent.Await.result(system.whenTerminated, 10.seconds)
          ()
        }
      case Failure(ex) =>
        log.error("Failed to bind HTTP server", ex)
        system.terminate()
    }
