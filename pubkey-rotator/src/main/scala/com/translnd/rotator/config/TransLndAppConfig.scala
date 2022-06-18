package com.translnd.rotator.config

import akka.actor.ActorSystem
import com.translnd.rotator.db._
import com.typesafe.config.Config
import grizzled.slf4j.Logging
import org.bitcoins.commons.config._
import org.bitcoins.core.hd.HDPurposes
import org.bitcoins.core.wallet.keymanagement._
import org.bitcoins.crypto._
import org.bitcoins.db._
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import org.bitcoins.keymanager.config.KeyManagerAppConfig

import java.nio.file._
import scala.concurrent._
import scala.util.Properties

case class TransLndAppConfig(
    private val directory: Path,
    override val configOverrides: Vector[Config])(implicit system: ActorSystem)
    extends DbAppConfig
    with JdbcProfileComponent[TransLndAppConfig]
    with DbManagement
    with Logging {
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  override val moduleName: String = TransLndAppConfig.moduleName
  override type ConfigType = TransLndAppConfig

  override val appConfig: TransLndAppConfig = this

  import profile.api._

  override def newConfigOfType(configs: Vector[Config]): TransLndAppConfig =
    TransLndAppConfig(directory, configs)

  val baseDatadir: Path = directory

  lazy val kmConf: KeyManagerAppConfig =
    KeyManagerAppConfig(baseDatadir, configOverrides)

  /** The path to our encrypted mnemonic seed */
  lazy val seedPath: Path = kmConf.seedPath

  override def start(): Future[Unit] = {
    logger.info(s"Initializing setup")

    if (Files.notExists(baseDatadir)) {
      Files.createDirectories(baseDatadir)
    }

    if (!kmConf.seedExists()) {
      BIP39KeyManager.initialize(aesPasswordOpt = aesPasswordOpt,
                                 kmParams = kmParams,
                                 bip39PasswordOpt = bip39PasswordOpt)
    }

    val numMigrations = migrate().migrationsExecuted
    logger.info(s"Applied $numMigrations")

    Future.unit
  }

  override def stop(): Future[Unit] = Future.unit

  lazy val kmParams: KeyManagerParams =
    KeyManagerParams(kmConf.seedPath, HDPurposes.SegWit, network)

  lazy val aesPasswordOpt: Option[AesPassword] = kmConf.aesPasswordOpt
  lazy val bip39PasswordOpt: Option[String] = kmConf.bip39PasswordOpt

  override lazy val dbPath: Path = baseDatadir

  override val allTables: List[TableQuery[Table[_]]] = {
    val invoiceTable: TableQuery[Table[_]] = InvoiceDAO()(ec, this).table
    val channelIdTable: TableQuery[Table[_]] = ChannelIdDAO()(ec, this).table

    List(invoiceTable, channelIdTable)
  }
}

object TransLndAppConfig
    extends AppConfigFactoryBase[TransLndAppConfig, ActorSystem] {

  val DEFAULT_DATADIR: Path = Paths.get(Properties.userHome, ".translnd")

  override def fromDefaultDatadir(confs: Vector[Config] = Vector.empty)(implicit
      ec: ActorSystem): TransLndAppConfig = {
    fromDatadir(DEFAULT_DATADIR, confs)
  }

  override def fromDatadir(datadir: Path, confs: Vector[Config])(implicit
      ec: ActorSystem): TransLndAppConfig =
    TransLndAppConfig(datadir, confs)

  override val moduleName: String = "translnd"
}
