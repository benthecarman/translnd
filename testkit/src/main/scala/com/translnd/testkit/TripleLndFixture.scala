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

trait TripleLndFixture
    extends BitcoinSFixture
    with CachedBitcoindV21
    with EmbeddedPg {

  override type FixtureParam =
    (BitcoindRpcClient, LndRpcClient, HTLCInterceptor, LndRpcClient)

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
            val conf = TransLndAppConfig.fromDatadir(parent, Vector(pg))
            conf.start().map { _ =>
              new HTLCInterceptor(lnds._2)(conf)
            }
          }
        } yield (bitcoind, lnds._1, htlc, lnds._3)
      },
      { param =>
        val (_, lndA, htlc, lndC) = param
        for {
          _ <- lndA.stop()
          _ <- htlc.lnd.stop()
          _ <- lndC.stop()
        } yield ()
      }
    )(test)
  }
}
