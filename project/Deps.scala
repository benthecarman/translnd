import sbt._

object Deps {

  object V {
    val bitcoinsV = "1.9.6-17-f4fff050-SNAPSHOT"

    val grizzledSlf4jV = "1.3.4"
  }

  object Compile {

    val grizzledSlf4j =
      "org.clapper" %% "grizzled-slf4j" % V.grizzledSlf4jV withSources () withJavadoc ()

    val bitcoinsCore =
      "org.bitcoin-s" %% "bitcoin-s-core" % V.bitcoinsV withSources () withJavadoc ()

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

  val sphinx: List[ModuleID] = List(Compile.bitcoinsCore)

  val channelIds: List[ModuleID] = List(Compile.bitcoinsAppCommons)

  val pubkeyRotator: List[ModuleID] =
    List(Compile.bitcoinsKeyManager,
         Compile.bitcoinsLnd,
         Compile.bitcoinsDbCommons)

  val testkit: List[ModuleID] = List(Compile.bitcoinsTestkit)

}
