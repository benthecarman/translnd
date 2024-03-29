import java.nio.file.Files
import java.security.MessageDigest
import scala.collection.JavaConverters._
import scala.concurrent._
import scala.concurrent.duration.DurationInt
import scala.util.Properties

enablePlugins(ReproducibleBuildsPlugin)

ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
ThisBuild / sonatypeRepository := "https://s01.oss.sonatype.org/service/local"

lazy val translnd = project
  .in(file("."))
  .aggregate(
    sphinx,
    sphinxTest,
    channelIds,
    channelIdsTest,
    pubkeyRotator,
    pubkeyRotatorTest,
    testkit
  )
  .dependsOn(
    sphinx,
    sphinxTest,
    channelIds,
    channelIdsTest,
    pubkeyRotator,
    pubkeyRotatorTest,
    testkit
  )
  .settings(CommonSettings.settings: _*)
  .settings(
    name := "translnd",
    publish / skip := true
  )

lazy val sphinx = project
  .in(file("sphinx"))
  .settings(CommonSettings.settings: _*)
  .settings(name := "sphinx", libraryDependencies ++= Deps.sphinx)

lazy val sphinxTest = project
  .in(file("sphinx-test"))
  .settings(CommonSettings.testSettings: _*)
  .settings(name := "sphinx-test", libraryDependencies ++= Deps.testkit)
  .dependsOn(sphinx)

lazy val channelIds = project
  .in(file("channel-ids"))
  .settings(CommonSettings.settings: _*)
  .settings(name := "channel-ids", libraryDependencies ++= Deps.channelIds)

lazy val channelIdsTest = project
  .in(file("channel-ids-test"))
  .settings(CommonSettings.testSettings: _*)
  .settings(name := "channel-ids-test", libraryDependencies ++= Deps.testkit)
  .dependsOn(channelIds)

lazy val pubkeyRotator = project
  .in(file("pubkey-rotator"))
  .settings(CommonSettings.settings: _*)
  .settings(name := "pubkey-rotator",
            libraryDependencies ++= Deps.pubkeyRotator)
  .dependsOn(sphinx, channelIds)

lazy val pubkeyRotatorTest = project
  .in(file("pubkey-rotator-test"))
  .settings(CommonSettings.testSettings: _*)
  .settings(name := "pubkey-rotator-test")
  .dependsOn(pubkeyRotator, testkit)

lazy val testkit = project
  .in(file("testkit"))
  .settings(CommonSettings.testSettings: _*)
  .settings(name := "testkit", libraryDependencies ++= Deps.testkit)
  .dependsOn(pubkeyRotator)

TaskKeys.downloadLnd := {
  val logger = streams.value.log
  import scala.sys.process._

  val binaryDir = CommonSettings.binariesPath.resolve("lnd")

  if (Files.notExists(binaryDir)) {
    logger.info(s"Creating directory for lnd binaries: $binaryDir")
    Files.createDirectories(binaryDir)
  }

  val version = "0.15.4-beta"

  val (platform, suffix) =
    if (Properties.isLinux) ("linux-amd64", "tar.gz")
    else if (Properties.isMac && System.getProperty("os.arch") == "aarch64")
      ("darwin-arm64", "tar.gz")
    else if (Properties.isMac) ("darwin-amd64", "tar.gz")
    else if (Properties.isWin) ("windows-amd64", "zip")
    else sys.error(s"Unsupported OS: ${Properties.osName}")

  logger.debug(s"(Maybe) downloading lnd binaries for version: $version")

  val versionDir = binaryDir resolve s"lnd-$platform-v$version"
  val location =
    s"https://github.com/lightningnetwork/lnd/releases/download/v$version/lnd-$platform-v$version.$suffix"

  if (Files.exists(versionDir)) {
    logger.debug(
      s"Directory $versionDir already exists, skipping download of lnd $version")
  } else {
    val archiveLocation = binaryDir resolve s"$version.$suffix"
    logger.info(s"Downloading lnd version $version from location: $location")
    logger.info(s"Placing the file in $archiveLocation")
    val downloadCommand = url(location) #> archiveLocation.toFile
    downloadCommand.!!

    val bytes = Files.readAllBytes(archiveLocation)
    val hash = MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map("%02x" format _)
      .mkString

    val expectedHash =
      if (Properties.isLinux)
        "60f419568afc06210e28c40361d9770f1495ed85a9da68da5309f2ffa2a5c016"
      else if (Properties.isMac && System.getProperty("os.arch") == "aarch64")
        "30bdfa85d45bb973710534fa5a27bb881603d5f7c717d4e28ce7feaa7797124b"
      else if (Properties.isMac)
        "fa2141a86f5f61b8ab20a8471f5f62986641d2d30833a3e7bdfaeba1ace28001"
      else if (Properties.isWin)
        "3c21ab4bdc5e5a3d907ede875617993250cc426e2da077fb40a1caf71694d46e"
      else sys.error(s"Unsupported OS: ${Properties.osName}")

    val success = hash.equalsIgnoreCase(expectedHash)
    if (success) {
      logger.info(s"Download complete and verified, unzipping result")

      val extractCommand = s"tar -xzf $archiveLocation --directory $binaryDir"
      logger.info(s"Extracting archive with command: $extractCommand")
      extractCommand.!!
    } else {
      logger.error(
        s"Downloaded invalid version of lnd, got $hash, expected $expectedHash")
    }

    logger.info(s"Deleting archive")
    Files.delete(archiveLocation)

    if (!success) {
      throw new RuntimeException(s"Failed to download lnd v$version")
    }
  }
}

TaskKeys.downloadBitcoind := {
  val logger = streams.value.log
  import scala.sys.process._

  val binaryDir = CommonSettings.binariesPath.resolve("bitcoind")

  if (Files.notExists(binaryDir)) {
    logger.info(s"Creating directory for bitcoind binaries: $binaryDir")
    Files.createDirectories(binaryDir)
  }

  val versions =
    List("24.0", "23.0", "22.0", "0.21.1", "0.20.1", "0.19.0.1")

  logger.debug(
    s"(Maybe) downloading Bitcoin Core binaries for versions: ${versions.mkString(",")}")

  val arm64MacVersions = List("24.0", "23.0")
  def getPlatformAndSuffix(version: String): (String, String) = {
    if (Properties.isLinux) ("x86_64-linux-gnu", "tar.gz")
    else if (Properties.isMac) {
      if (arm64MacVersions.contains(version)) {
        if (System.getProperty("os.arch") == "aarch64")
          ("arm64-apple-darwin", "tar.gz")
        else ("x86_64-apple-darwin", "tar.gz")
      } else ("osx64", "tar.gz")
    } else if (Properties.isWin) ("win64", "zip")
    else sys.error(s"Unsupported OS: ${Properties.osName}")
  }

  implicit val ec = scala.concurrent.ExecutionContext.global
  val downloads = versions.map { version =>
    val (platform, suffix) = getPlatformAndSuffix(version)
    val archiveLocation = binaryDir resolve s"$version.$suffix"
    val location =
      if (version.init.endsWith("rc")) { // if it is a release candidate
        val (base, rc) = version.splitAt(version.length - 3)
        s"https://bitcoincore.org/bin/bitcoin-core-$base/test.$rc/bitcoin-$version-$platform.$suffix"
      } else
        s"https://bitcoincore.org/bin/bitcoin-core-$version/bitcoin-$version-$platform.$suffix"

    val expectedEndLocation = binaryDir resolve s"bitcoin-$version"

    if (
      Files
        .list(binaryDir)
        .iterator
        .asScala
        .map(_.toString)
        .exists(expectedEndLocation.toString.startsWith(_))
    ) {
      logger.debug(
        s"Directory $expectedEndLocation already exists, skipping download of version $version")
      Future.unit
    } else {
      // copy of FutureUtil.makeAsync
      def makeAsync(func: () => Unit): Future[Unit] = {
        val resultP = Promise[Unit]()

        ec.execute { () =>
          val result: Unit = func()
          resultP.success(result)
        }

        resultP.future
      }

      makeAsync { () =>
        logger.info(
          s"Downloading bitcoind version $version from location: $location")
        logger.info(s"Placing the file in $archiveLocation")
        val downloadCommand = url(location) #> archiveLocation.toFile
        downloadCommand.!!

        val bytes = Files.readAllBytes(archiveLocation)
        val hash = MessageDigest
          .getInstance("SHA-256")
          .digest(bytes)
          .map("%02x" format _)
          .mkString

        val expectedHash =
          if (Properties.isLinux)
            Map(
              "24.0" -> "fb86cf6af7a10bc5f3ae6cd6a5b0348854e1462102fe71e755d30b51b6e317d1",
              "23.0" -> "2cca490c1f2842884a3c5b0606f179f9f937177da4eadd628e3f7fd7e25d26d0",
              "22.0" -> "59ebd25dd82a51638b7a6bb914586201e67db67b919b2a1ff08925a7936d1b16",
              "0.21.1" -> "366eb44a7a0aa5bd342deea215ec19a184a11f2ca22220304ebb20b9c8917e2b",
              "0.20.1" -> "376194f06596ecfa40331167c39bc70c355f960280bd2a645fdbf18f66527397",
              "0.19.0.1" -> "732cc96ae2e5e25603edf76b8c8af976fe518dd925f7e674710c6c8ee5189204"
            )
          else if (Properties.isMac)
            Map(
              "24.0" -> (if (System.getProperty("os.arch") == "aarch64")
                           "03cbad3118ead19de6664ab80c43b6ee97302109e8789ca9a7e261bdc0aa6937"
                         else
                           "a2b7ba863e5536249a8e97b464bc9a91ad73a0b6340434bf1c09aa912ac52f29"),
              "23.0" -> (if (System.getProperty("os.arch") == "aarch64")
                           "7c8bc63731aa872b7b334a8a7d96e33536ad77d49029bad179b09dca32cd77ac"
                         else
                           "c816780583009a9dad426dc0c183c89be9da98906e1e2c7ebae91041c1aaaaf3"),
              "22.0" -> "2744d199c3343b2d94faffdfb2c94d75a630ba27301a70e47b0ad30a7e0155e9",
              "0.21.1" -> "1ea5cedb64318e9868a66d3ab65de14516f9ada53143e460d50af428b5aec3c7",
              "0.20.1" -> "b9024dde373ea7dad707363e07ec7e265383204127539ae0c234bff3a61da0d1",
              "0.19.0.1" -> "a64e4174e400f3a389abd76f4d6b1853788730013ab1dedc0e64b0a0025a0923"
            )
          else if (Properties.isWin)
            Map(
              "24.0" -> "09fa27956f2884434eee2ca1a9d2822712e8405c096e82ecedf64a638f42ca1a",
              "23.0" -> "004b2e25b21e0f14cbcce6acec37f221447abbb3ea7931c689e508054bfc6cf6",
              "22.0" -> "9485e4b52ed6cebfe474ab4d7d0c1be6d0bb879ba7246a8239326b2230a77eb1",
              "0.21.1" -> "94c80f90184cdc7e7e75988a55b38384de262336abd80b1b30121c6e965dc74e",
              "0.20.1" -> "e59fba67afce011d32b5d723a3a0be12da1b8a34f5d7966e504520c48d64716d",
              "0.19.0.1" -> "7706593de727d893e4b1e750dc296ea682ccee79acdd08bbc81eaacf3b3173cf"
            )
          else sys.error(s"Unsupported OS: ${Properties.osName}")

        val success = hash.equalsIgnoreCase(expectedHash(version))
        if (success) {
          logger.info(s"Download complete and verified, unzipping result")

          val extractCommand =
            s"tar -xzf $archiveLocation --directory $binaryDir"
          logger.info(s"Extracting archive with command: $extractCommand")
          extractCommand.!!
        } else {
          logger.error(
            s"Downloaded invalid version of bitcoind v$version, got $hash, expected ${expectedHash(version)}")
        }

        logger.info(s"Deleting archive")
        Files.delete(archiveLocation)

        if (!success)
          throw new RuntimeException(s"Failed to download bitcoind v$version")
      }

    }
  }

  // timeout if we cannot download in 5 minutes
  Await.result(Future.sequence(downloads), 5.minutes)
}
