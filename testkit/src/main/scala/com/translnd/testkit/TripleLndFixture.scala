package com.translnd.testkit

import com.translnd.rotator.PubkeyRotator
import com.translnd.rotator.config.TransLndAppConfig
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.lnd.rpc.config.LndInstanceLocal
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.testkit.BitcoinSTestAppConfig.configWithEmbeddedDb
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.rpc.CachedBitcoindV23
import org.scalatest.FutureOutcome

import scala.concurrent.Future

trait TripleLndFixture
    extends BitcoinSFixture
    with CachedBitcoindV23
    with EmbeddedPg {

  override type FixtureParam =
    (BitcoindRpcClient, LndRpcClient, PubkeyRotator, LndRpcClient)

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[FixtureParam](
      () => {
        for {
          bitcoind <- cachedBitcoindWithFundsF
          _ = logger.debug("creating lnds")
          lnds <- TestUtil.createNodeTriple(bitcoind)
          htlc <- {
            val parent =
              lnds._2.instance.asInstanceOf[LndInstanceLocal].datadir.getParent

            val pg = configWithEmbeddedDb(None, () => pgUrl())
            implicit val conf: TransLndAppConfig =
              TransLndAppConfig.fromDatadir(parent, Vector(pg))

            conf.start().map(_ => PubkeyRotator(Vector(lnds._2)))
          }
        } yield (bitcoind, lnds._1, htlc, lnds._3)
      },
      { param =>
        val (_, lndA, htlc, lndC) = param
        val config = htlc.config

        for {
          _ <- config.dropAll().map(_ => config.clean())
          _ <- lndA.stop()
          _ <- lndA.system.terminate()
          _ <- Future.sequence(htlc.lnds.map(_.stop()))
          _ <- Future.sequence(htlc.lnds.map(_.system.terminate()))
          _ <- lndC.stop()
          _ <- lndC.system.terminate()
        } yield ()
      }
    )(test)
  }
}
