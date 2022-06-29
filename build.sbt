import java.nio.file.Files
import java.security.MessageDigest
import scala.collection.JavaConverters._
import scala.concurrent._
import scala.concurrent.duration.DurationInt
import scala.util.Properties

enablePlugins(ReproducibleBuildsPlugin,
              JavaAppPackaging,
              GraalVMNativeImagePlugin,
              DockerPlugin,
              WindowsPlugin)

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

  val version = "0.15.0-beta"

  val (platform, suffix) =
    if (Properties.isLinux) ("linux-amd64", "tar.gz")
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
        "60511b4717a82c303e164f7d1048fd52f965c5fcb7aefaa11678be48e81a9dcc"
      else if (Properties.isMac)
        "eacf6e8f19de942fb23f481c85541342d3248bd545616822a172da3d23c03c9d"
      else if (Properties.isWin)
        "74c16854292ca27b335309d7b37a44baec4df402e16b203df393ebece6570ad3"
      else sys.error(s"Unsupported OS: ${Properties.osName}")

    if (hash.equalsIgnoreCase(expectedHash)) {
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

  val experimentalVersion = "0.18.99"
  val versions = List("23.0")

  logger.debug(
    s"(Maybe) downloading Bitcoin Core binaries for versions: ${versions.mkString(",")}")

  def getPlatformAndSuffix(version: String): (String, String) = {
    if (Properties.isLinux) ("x86_64-linux-gnu", "tar.gz")
    else if (Properties.isMac)
      version match {
        case "23.0" => ("x86_64-apple-darwin", "tar.gz")
        case _      => ("osx64", "tar.gz")
      }
    else if (Properties.isWin) ("win64", "zip")
    else sys.error(s"Unsupported OS: ${Properties.osName}")
  }

  implicit val ec: ExecutionContextExecutor =
    scala.concurrent.ExecutionContext.global
  val downloads = versions.map { version =>
    val (platform, suffix) = getPlatformAndSuffix(version)
    val archiveLocation = binaryDir resolve s"$version.$suffix"
    val location =
      if (version == experimentalVersion)
        s"https://s3-us-west-1.amazonaws.com/suredbits.com/bitcoin-core-$version/bitcoin-$version-$platform.$suffix"
      else if (version.init.endsWith("rc")) { // if it is a release candidate
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
              "23.0" -> "2cca490c1f2842884a3c5b0606f179f9f937177da4eadd628e3f7fd7e25d26d0",
              "22.0" -> "59ebd25dd82a51638b7a6bb914586201e67db67b919b2a1ff08925a7936d1b16",
              "0.21.1" -> "366eb44a7a0aa5bd342deea215ec19a184a11f2ca22220304ebb20b9c8917e2b",
              "0.20.1" -> "376194f06596ecfa40331167c39bc70c355f960280bd2a645fdbf18f66527397",
              "0.19.0.1" -> "732cc96ae2e5e25603edf76b8c8af976fe518dd925f7e674710c6c8ee5189204",
              "0.18.1" -> "600d1db5e751fa85903e935a01a74f5cc57e1e7473c15fd3e17ed21e202cfe5a",
              "0.17.0.1" -> "6ccc675ee91522eee5785457e922d8a155e4eb7d5524bd130eb0ef0f0c4a6008",
              "0.16.3" -> "5d422a9d544742bc0df12427383f9c2517433ce7b58cf672b9a9b17c2ef51e4f",
              experimentalVersion -> "f8b1a0ded648249e5e8c14fca3e11a733da8172b05523922c87557ea5eaaa4c5"
            )
          else if (Properties.isMac)
            Map(
              "23.0" -> "c816780583009a9dad426dc0c183c89be9da98906e1e2c7ebae91041c1aaaaf3",
              "22.0" -> "2744d199c3343b2d94faffdfb2c94d75a630ba27301a70e47b0ad30a7e0155e9",
              "0.21.1" -> "1ea5cedb64318e9868a66d3ab65de14516f9ada53143e460d50af428b5aec3c7",
              "0.20.1" -> "b9024dde373ea7dad707363e07ec7e265383204127539ae0c234bff3a61da0d1",
              "0.19.0.1" -> "a64e4174e400f3a389abd76f4d6b1853788730013ab1dedc0e64b0a0025a0923",
              "0.18.1" -> "b7bbcee7a7540f711b171d6981f939ca8482005fde22689bc016596d80548bb1",
              "0.17.0.1" -> "3b1fb3dd596edb656bbc0c11630392e201c1a4483a0e1a9f5dd22b6556cbae12",
              "0.16.3" -> "78c3bff3b619a19aed575961ea43cc9e142959218835cf51aede7f0b764fc25d",
              experimentalVersion -> "cfd4ed0b8db08fb1355aca44ca282b1de31e83b5862efaac527e3952b0987e55"
            )
          else if (Properties.isWin)
            Map(
              "23.0" -> "004b2e25b21e0f14cbcce6acec37f221447abbb3ea7931c689e508054bfc6cf6",
              "22.0" -> "9485e4b52ed6cebfe474ab4d7d0c1be6d0bb879ba7246a8239326b2230a77eb1",
              "0.21.1" -> "94c80f90184cdc7e7e75988a55b38384de262336abd80b1b30121c6e965dc74e",
              "0.20.1" -> "e59fba67afce011d32b5d723a3a0be12da1b8a34f5d7966e504520c48d64716d",
              "0.19.0.1" -> "7706593de727d893e4b1e750dc296ea682ccee79acdd08bbc81eaacf3b3173cf",
              "0.18.1" -> "b0f94ab43c068bac9c10a59cb3f1b595817256a00b84f0b724f8504b44e1314f",
              "0.17.0.1" -> "2d0a0aafe5a963beb965b7645f70f973a17f4fa4ddf245b61d532f2a58449f3e",
              "0.16.3" -> "52469c56222c1b5344065ef2d3ce6fc58ae42939a7b80643a7e3ee75ec237da9",
              experimentalVersion -> "b7ad8e6c0b91adf820499bf891f22f590e969b834980d4910efac5b082d83c49"
            )
          else sys.error(s"Unsupported OS: ${Properties.osName}")

        if (hash.equalsIgnoreCase(expectedHash(version))) {
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
      }

    }
  }

  //timeout if we cannot download in 5 minutes
  Await.result(Future.sequence(downloads), 5.minutes)
}
