package com.translnd.testkit

import com.translnd.rotator.PubkeyRotator
import com.translnd.rotator.config._
import com.typesafe.config.ConfigFactory
import org.bitcoins.lnd.rpc.config.LndInstanceLocal
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.testkit.BitcoinSTestAppConfig.ProjectType.Unknown
import org.bitcoins.testkit.BitcoinSTestAppConfig.configWithEmbeddedDb
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.lnd.LndRpcTestClient
import org.bitcoins.testkit.rpc.CachedBitcoindV23
import org.scalatest.FutureOutcome

import scala.concurrent.Future

trait PubkeyRotatorFixture
    extends BitcoinSFixture
    with CachedBitcoindV23
    with TestUtil
    with EmbeddedPg {

  def pubkeyPrefixOpt: Option[String]

  override type FixtureParam = (BitcoindRpcClient, PubkeyRotator)

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[FixtureParam](
      () => {
        for {
          bitcoind <- cachedBitcoindWithFundsF
          _ = logger.debug("creating lnd")
          lndTestClient = LndRpcTestClient.fromSbtDownload(Some(bitcoind))
          lnd <- lndTestClient.start()
          htlc <- {
            val parent =
              lnd.instance.asInstanceOf[LndInstanceLocal].datadir.getParent

            val pubkeyPrefixConfig = pubkeyPrefixOpt match {
              case Some(prefix) =>
                ConfigFactory.parseString(
                  s"""
                     |translnd.pubkey-prefix = "$prefix"
                     |""".stripMargin
                )
              case None => ConfigFactory.empty()
            }

            val pg =
              configWithEmbeddedDb(Some(Unknown(TransLndAppConfig.moduleName)),
                                   () => pgUrl())
            implicit val conf: TransLndAppConfig =
              TransLndAppConfig.fromDatadir(parent,
                                            Vector(pubkeyPrefixConfig, pg))

            conf.start().map(_ => PubkeyRotator(Vector(lnd)))
          }
        } yield (bitcoind, htlc)
      },
      { param =>
        val (_, htlc) = param
        val config = htlc.config

        for {
          _ <- config.dropAll().map(_ => config.clean())
          _ <- Future.sequence(htlc.lnds.map(_.stop()))
        } yield ()
      }
    )(test)
  }
}
