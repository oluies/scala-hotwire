ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "se.olund.hotwire"
ThisBuild / version      := "0.1.0-SNAPSHOT"

val pekkoV     = "1.6.0"
val pekkoHttpV = "1.3.0"
val jnatsV     = "2.26.0"
val munitV     = "1.3.4"

lazy val root = (project in file("."))
  .enablePlugins(SbtTwirl)
  .settings(
    name := "scala-hotwire",

    scalacOptions ++= Seq(
      "-encoding", "utf8",
      "-feature",
      "-unchecked",
      "-deprecation",
      "-Wunused:all",
      // Twirl's generated sources carry imports the template may not use;
      // -Wunused:all flags them against the .scala.html, which we can't fix.
      "-Wconf:src=.*/twirl/.*:s"
    ),

    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-http"           % pekkoHttpV,
      "org.apache.pekko" %% "pekko-stream"         % pekkoV,
      "org.apache.pekko" %% "pekko-actor-typed"    % pekkoV,
      "org.apache.pekko" %% "pekko-slf4j"          % pekkoV,
      "io.nats"           % "jnats"                % jnatsV,
      "ch.qos.logback"    % "logback-classic"      % "1.5.38",

      "org.scalameta"    %% "munit"                % munitV     % Test,
      "org.apache.pekko" %% "pekko-http-testkit"   % pekkoHttpV % Test,
      "org.apache.pekko" %% "pekko-stream-testkit" % pekkoV     % Test,
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoV % Test
    ),

    Compile / mainClass := Some("hotwire.Main"),
    run / fork := true,
    Test / fork := true,

    testFrameworks += new TestFramework("munit.Framework")
  )
