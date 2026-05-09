ThisBuild / scalaVersion := "3.3.4"
ThisBuild / organization := "se.olund.hotwire"
ThisBuild / version      := "0.1.0-SNAPSHOT"

val pekkoV     = "1.1.3"
val pekkoHttpV = "1.1.0"
val jnatsV     = "2.20.6"
val munitV     = "1.0.3"

lazy val root = (project in file("."))
  .enablePlugins(SbtTwirl)
  .settings(
    name := "scala-hotwire",

    scalacOptions ++= Seq(
      "-encoding", "utf8",
      "-feature",
      "-unchecked",
      "-deprecation",
      "-Wunused:all"
    ),

    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-http"           % pekkoHttpV,
      "org.apache.pekko" %% "pekko-stream"         % pekkoV,
      "org.apache.pekko" %% "pekko-actor-typed"    % pekkoV,
      "org.apache.pekko" %% "pekko-slf4j"          % pekkoV,
      "io.nats"           % "jnats"                % jnatsV,
      "ch.qos.logback"    % "logback-classic"      % "1.5.12",

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
