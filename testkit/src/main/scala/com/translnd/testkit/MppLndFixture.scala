package com.translnd.testkit

import com.translnd.htlc.HTLCInterceptor
import com.translnd.htlc.config.TransLndAppConfig
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.lnd.rpc.config.LndInstanceLocal
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.testkit.BitcoinSTestAppConfig.configWithEmbeddedDb
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.rpc.CachedBitcoindV21
import org.scalatest.FutureOutcome

import scala.concurrent.Future

trait MppLndFixture
    extends BitcoinSFixture
    with CachedBitcoindV21
    with TestUtil
    with EmbeddedPg {

  override type FixtureParam =
    (BitcoindRpcClient, LndRpcClient, HTLCInterceptor)

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    makeDependentFixture[FixtureParam](
      () => {
        for {
          bitcoind <- cachedBitcoindWithFundsF
          _ = logger.debug("creating lnds")
          lnds <- TestUtil.createMPPLnds(bitcoind)
          htlc <- {
            val parent =
              lnds._2.instance.asInstanceOf[LndInstanceLocal].datadir.getParent

            val pg = configWithEmbeddedDb(None, () => pgUrl())
            implicit val conf: TransLndAppConfig =
              TransLndAppConfig.fromDatadir(parent, Vector(pg))

            conf.start().map { _ =>
              new HTLCInterceptor(Vector(lnds._2))
            }
          }
          _ = htlc.start()
        } yield (bitcoind, lnds._1, htlc)
      },
      { param =>
        val (_, lndA, htlc) = param
        for {
          _ <- lndA.stop()
          _ <- Future.sequence(htlc.lnds.map(_.stop()))
        } yield ()
      }
    )(test)
  }
}
