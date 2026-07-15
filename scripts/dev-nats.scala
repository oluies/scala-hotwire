//| scalaVersion: 3.8.4
//| mvnDeps: []

// Run the NATS broker this project's NATS/JetStream demos need, in a container,
// using Apple's `container` runtime (https://github.com/apple/container).
//
// The app itself still runs on the host via `sbt run` -- only the broker is
// containerised, which is the part the README otherwise asks you to `brew
// install`. JetStream is enabled (-js) because the /jetstream-chat demo needs it.
//
//   mill scripts/dev-nats.scala up       # start broker (idempotent)
//   mill scripts/dev-nats.scala down     # stop and remove it
//   mill scripts/dev-nats.scala status   # is it up, and is JetStream on?
//   mill scripts/dev-nats.scala logs     # follow broker logs
//   mill scripts/dev-nats.scala run      # up, then `sbt run` wired to it
//
// The container is named `nats-hotwire`, so it shows up under that name in
// Davit (https://github.com/wouterdebie/davit) -- Davit is a GUI over this same
// daemon, so anything started here appears there and vice versa.

import java.net.{InetSocketAddress, Socket}

val Name        = "nats-hotwire"
val Image       = "docker.io/library/nats:latest"
val ClientPort  = 4222
val MonitorPort = 8222
val NatsUrl     = s"nats://localhost:$ClientPort"

def die(msg: String): Nothing =
  System.err.println(s"error: $msg")
  sys.exit(1)

def requireContainerCli(): Unit =
  val r = os.proc("which", "container").call(check = false)
  if r.exitCode != 0 then
    die("the 'container' CLI is not installed. See https://github.com/apple/container (brew install container)")

/** The daemon is not running after a reboot, and every other subcommand fails
  * with an opaque XPC error if it is down -- so check and start it first.
  */
def ensureDaemon(): Unit =
  val r = os.proc("container", "system", "status").call(check = false)
  if r.exitCode != 0 then
    println("==> starting container system service")
    os.proc("container", "system", "start").call(stdout = os.Inherit, stderr = os.Inherit)

/** One entry of `container ls --all --format json`, reduced to what we need. */
case class Entry(id: String, state: String)

def entries(): Seq[Entry] =
  val r = os.proc("container", "ls", "--all", "--format", "json").call(check = false)
  if r.exitCode != 0 then Seq.empty
  else
    // Shape (verified against container 1.1.0): [{ "id": ..., "status": { "state": ... } }]
    ujson.read(r.out.text()).arr.toSeq.flatMap { v =>
      for
        id    <- v.obj.get("id").flatMap(_.strOpt)
        state <- v.obj.get("status").flatMap(_.obj.get("state")).flatMap(_.strOpt)
      yield Entry(id, state)
    }

def find(): Option[Entry] = entries().find(_.id == Name)
def isRunning(): Boolean  = find().exists(_.state == "running")

/** Connect and read NATS's INFO line -- this both proves the port is reachable
  * and reports whether JetStream is genuinely on, rather than trusting that a
  * running container means a working broker.
  */
def natsInfo(timeoutMs: Int = 1500): Option[String] =
  val sock = new Socket()
  try
    sock.connect(new InetSocketAddress("localhost", ClientPort), timeoutMs)
    sock.setSoTimeout(timeoutMs)
    val buf = new Array[Byte](512)
    val n   = sock.getInputStream.read(buf)
    if n > 0 then Some(String(buf, 0, n, "UTF-8")) else None
  catch case _: Throwable => None
  finally try sock.close() catch case _: Throwable => ()

/** `container run -d` returns once the VM is up, which is before NATS accepts
  * clients. Poll rather than sleeping a fixed guess.
  */
def waitReady(): Unit =
  print(s"==> waiting for NATS on $NatsUrl ")
  val deadline = System.currentTimeMillis() + 60_000
  while System.currentTimeMillis() < deadline do
    if natsInfo(500).isDefined then
      println()
      println(s"==> ready: $NatsUrl")
      return
    print(".")
    Console.flush()
    Thread.sleep(1000)
  println()
  die(s"NATS did not come up within 60s. Try: mill scripts/dev-nats.scala logs")

@main
def up(): Unit =
  requireContainerCli()
  ensureDaemon()

  if isRunning() then
    println(s"==> $Name already running on $NatsUrl")
  else
    // A stopped container of the same name makes `run` fail on the name
    // collision, so clear it out first.
    if find().isDefined then
      println(s"==> removing stopped $Name")
      os.proc("container", "rm", Name).call(check = false)

    println(s"==> starting $Name ($Image, JetStream enabled)")
    os.proc(
      "container", "run", "-d",
      "--name", Name,
      "-p", s"$ClientPort:$ClientPort",
      "-p", s"$MonitorPort:$MonitorPort",
      Image, "-js"
    ).call(stdout = os.Inherit, stderr = os.Inherit)

    waitReady()

@main
def down(): Unit =
  requireContainerCli()
  ensureDaemon()
  if find().isDefined then
    println(s"==> stopping and removing $Name")
    os.proc("container", "stop", Name).call(check = false)
    os.proc("container", "rm", Name).call(check = false)
  else println(s"==> $Name not present")

@main
def status(): Unit =
  requireContainerCli()
  ensureDaemon()

  find() match
    case Some(e) => println(s"==> $Name: ${e.state}")
    case None    => println(s"==> $Name not present")

  natsInfo() match
    case Some(info) if info.contains("\"jetstream\":true")  => println(s"==> $NatsUrl reachable, JetStream: enabled")
    case Some(info) if info.contains("\"jetstream\":false") => println(s"==> $NatsUrl reachable, JetStream: DISABLED (needs -js)")
    case Some(_)                                            => println(s"==> $NatsUrl reachable (no INFO read)")
    case None                                               => println(s"==> $NatsUrl not reachable")

@main
def logs(): Unit =
  requireContainerCli()
  ensureDaemon()
  os.proc("container", "logs", "-f", Name).call(stdout = os.Inherit, stderr = os.Inherit, check = false)

/** Wire the host-side app to the containerised broker. Pass --port to run a
  * second node for the fan-out demo: `... run --port 8081`.
  */
@main
def run(port: Int = 8080): Unit =
  up()
  val repoRoot = os.pwd
  println(s"==> NATS_URL=$NatsUrl PORT=$port sbt run")
  println(s"==> then open http://localhost:$port/chat/lobby")
  os.proc("sbt", "run")
    .call(
      cwd = repoRoot,
      env = Map("NATS_URL" -> NatsUrl, "PORT" -> port.toString),
      stdout = os.Inherit,
      stderr = os.Inherit,
      check = false
    )
