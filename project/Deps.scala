import sbt._

object Deps {

  lazy val arch: String = System.getProperty("os.arch")

  lazy val osName: String = System.getProperty("os.name") match {
    case n if n.startsWith("Linux") => "linux"
    case n if n.startsWith("Mac") =>
      if (arch == "aarch64") {
        //needed to accommodate the different chip
        //arch for M1. see: https://github.com/bitcoin-s/bitcoin-s/pull/3041
        s"mac-$arch"
      } else {
        "mac"
      }
    case n if n.startsWith("Windows") => "win"
    case x =>
      throw new Exception(s"Unknown platform $x!")
  }

  object V {
    val akkaV = "10.2.9"
    val akkaStreamV = "2.6.19"
    val akkaActorV: String = akkaStreamV

    val scalaFxV = "16.0.0-R25"
    val javaFxV = "17-ea+8"

    val bitcoinsV = "1.9.1-85-cbeae5cd-SNAPSHOT"

    val scoptV = "4.0.1"

    val sttpV = "1.7.2"

    val codehausV = "3.1.7"

    val microPickleV = "1.3.8"

    val logback = "1.2.11"
    val slf4j = "1.7.36"
    val grizzledSlf4jV = "1.3.4"
  }

  object Compile {

    val akkaHttp =
      "com.typesafe.akka" %% "akka-http" % V.akkaV withSources () withJavadoc ()

    val akkaStream =
      "com.typesafe.akka" %% "akka-stream" % V.akkaStreamV withSources () withJavadoc ()

    val akkaActor =
      "com.typesafe.akka" %% "akka-actor" % V.akkaStreamV withSources () withJavadoc ()

    val akkaSlf4j =
      "com.typesafe.akka" %% "akka-slf4j" % V.akkaStreamV withSources () withJavadoc ()

    val sttp =
      "com.softwaremill.sttp" %% "core" % V.sttpV withSources () withJavadoc ()

    val micoPickle =
      "com.lihaoyi" %% "upickle" % V.microPickleV withSources () withJavadoc ()

    val scopt =
      "com.github.scopt" %% "scopt" % V.scoptV withSources () withJavadoc ()

    val codehaus =
      "org.codehaus.janino" % "janino" % V.codehausV withSources () withJavadoc ()

    val grizzledSlf4j =
      "org.clapper" %% "grizzled-slf4j" % V.grizzledSlf4jV withSources () withJavadoc ()

    val slf4jApi =
      "org.slf4j" % "slf4j-api" % V.slf4j % "provided" withSources () withJavadoc ()

    val slf4jSimple =
      "org.slf4j" % "slf4j-simple" % V.slf4j % "provided" withSources () withJavadoc ()

    val logback =
      "ch.qos.logback" % "logback-classic" % V.logback withSources () withJavadoc ()

    val bitcoinsKeyManager =
      "org.bitcoin-s" %% "bitcoin-s-key-manager" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsLnd =
      "org.bitcoin-s" %% "bitcoin-s-lnd-rpc" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsTestkit =
      "org.bitcoin-s" %% "bitcoin-s-testkit" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsAppCommons =
      "org.bitcoin-s" %% "bitcoin-s-app-commons" % V.bitcoinsV withSources () withJavadoc ()

    val bitcoinsDbCommons =
      "org.bitcoin-s" %% "bitcoin-s-db-commons" % V.bitcoinsV withSources () withJavadoc ()
  }

  val htlcInterceptor: List[ModuleID] =
    List(Compile.bitcoinsKeyManager,
         Compile.bitcoinsLnd,
         Compile.bitcoinsDbCommons,
         Compile.grizzledSlf4j)

  val testkit: List[ModuleID] =
    List(Compile.bitcoinsTestkit)

}
