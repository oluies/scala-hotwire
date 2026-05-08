package hotwire

import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.slf4j.LoggerFactory

import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

object Main:
  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    val cfg = ConfigFactory.load().getConfig("hotwire")
    val host = cfg.getString("host")
    val port = cfg.getInt("port")
    val natsUrl = Try(cfg.getString("nats-url")).toOption.filter(_.nonEmpty)

    given system: ActorSystem[Nothing] =
      ActorSystem(Behaviors.empty, "hotwire")
    import system.executionContext

    val bus: BroadcastBus = natsUrl match
      case Some(url) =>
        log.info(s"Connecting to NATS at $url")
        new NatsBroadcastBus(NatsBroadcastBus.connect(url))
      case None =>
        log.info("Using in-process broadcast bus (set NATS_URL to use NATS)")
        new InProcessBroadcastBus()

    val chat = new ChatRoutes(bus)

    val routes =
      concat(
        chat.routes,
        pathPrefix("public") {
          getFromResourceDirectory("public")
        },
        pathSingleSlash {
          redirect("/chat/lobby", StatusCodes.TemporaryRedirect)
        }
      )

    Http().newServerAt(host, port).bind(routes).onComplete {
      case Success(binding) =>
        log.info(s"Listening on http://$host:$port  →  open /chat/lobby in two tabs")
        sys.addShutdownHook {
          log.info("Shutting down…")
          val done = for
            _ <- binding.terminate(5.seconds)
            _ <- bus.shutdown()
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
