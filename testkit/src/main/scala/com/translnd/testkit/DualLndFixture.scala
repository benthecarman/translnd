package com.translnd.testkit

import com.translnd.htlc.HTLCInterceptor
import com.translnd.htlc.config.TransLndAppConfig
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.lnd.rpc.config.LndInstanceLocal
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.testkit.BitcoinSTestAppConfig.configWithEmbeddedDb
import org.bitcoins.testkit.EmbeddedPg
import org.bitcoins.testkit.fixtures.BitcoinSFixture
import org.bitcoins.testkit.lnd.LndRpcTestUtil
import org.bitcoins.testkit.rpc.CachedBitcoindV21
import org.scalatest.FutureOutcome

import scala.concurrent.Future

trait DualLndFixture
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
          lnds <- LndRpcTestUtil.createNodePair(bitcoind,
                                                CHANNEL_SIZE,
                                                Satoshis.zero)
          _ <- LndRpcTestUtil.openChannel(bitcoind = bitcoind,
                                          n1 = lnds._1,
                                          n2 = lnds._2,
                                          amt = CHANNEL_SIZE,
                                          pushAmt = Satoshis.zero)
          htlc <- {
            val parent =
              lnds._2.instance.asInstanceOf[LndInstanceLocal].datadir.getParent

            val pg = configWithEmbeddedDb(None, () => pgUrl())
            implicit val conf: TransLndAppConfig =
              TransLndAppConfig.fromDatadir(parent, Vector(pg))

            conf.start().map(_ => HTLCInterceptor(Vector(lnds._2)))
          }
        } yield (bitcoind, lnds._1, htlc)
      },
      { param =>
        val (_, lndA, htlc) = param
        for {
          _ <- lndA.stop()
          _ <- lndA.system.terminate()
          _ <- Future.sequence(htlc.lnds.map(_.stop()))
          _ <- Future.sequence(htlc.lnds.map(_.system.terminate()))
        } yield ()
      }
    )(test)
  }
}
