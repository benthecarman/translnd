package com.translnd.testkit

import akka.actor.ActorSystem
import grizzled.slf4j.Logging
import lnrpc._
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.number._
import org.bitcoins.lnd.rpc._
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.testkit.async.TestAsyncUtil
import org.bitcoins.testkit.lnd.LndRpcTestClient
import org.bitcoins.testkit.lnd.LndRpcTestUtil._
import org.bitcoins.testkit.util.FileUtil

import scala.concurrent._
import scala.concurrent.duration.DurationInt

trait TestUtil extends Logging with LndUtils {

  def createNodeTriple(bitcoind: BitcoindRpcClient)(implicit
  system: ActorSystem): Future[(LndRpcClient, LndRpcClient, LndRpcClient)] = {
    import system.dispatcher

    val actorSystemA =
      ActorSystem.create("bitcoin-s-lnd-test-" + FileUtil.randomDirName)
    val clientA = LndRpcTestClient
      .fromSbtDownload(Some(bitcoind))(actorSystemA)

    val actorSystemB =
      ActorSystem.create("bitcoin-s-lnd-test-" + FileUtil.randomDirName)
    val clientB = LndRpcTestClient
      .fromSbtDownload(Some(bitcoind))(actorSystemB)

    val actorSystemC =
      ActorSystem.create("bitcoin-s-lnd-test-" + FileUtil.randomDirName)
    val clientC = LndRpcTestClient
      .fromSbtDownload(Some(bitcoind))(actorSystemC)

    val clientsF = for {
      a <- clientA.start()
      b <- clientB.start()
      c <- clientC.start()
    } yield (a, b, c)

    def isSynced: Future[Boolean] = for {
      (clientA, clientB, clientC) <- clientsF

      infoA <- clientA.getInfo
      infoB <- clientB.getInfo
      infoC <- clientC.getInfo
    } yield infoA.syncedToChain && infoB.syncedToChain && infoC.syncedToChain

    def knowsChannel: Future[Boolean] = for {
      (clientA, _, clientC) <- clientsF
      chan <- clientA.listChannels(ListChannelsRequest()).map(_.head)
      opt <- clientC.lnd.getChanInfo(ChanInfoRequest(chan.chanId))
      chan <- clientC.listChannels(ListChannelsRequest()).map(_.head)
      opt2 <- clientA.lnd.getChanInfo(ChanInfoRequest(chan.chanId))
    } yield opt.channelId > UInt64.zero && opt2.channelId > UInt64.zero

    def isFunded: Future[Boolean] = for {
      (clientA, clientB, clientC) <- clientsF

      balA <- clientA.walletBalance()
      balB <- clientB.walletBalance()
      balC <- clientC.walletBalance()
    } yield {
      balA.confirmedBalance > Satoshis.zero &&
      balB.confirmedBalance > Satoshis.zero &&
      balC.confirmedBalance > Satoshis.zero
    }

    for {
      (clientA, clientB, clientC) <- clientsF

      _ <- connectLNNodes(clientA, clientB)
      _ <- connectLNNodes(clientB, clientC)
      _ <- connectLNNodes(clientA, clientC)

      _ <- fundLNNodes(bitcoind, clientA, clientA)
      _ <- fundLNNodes(bitcoind, clientB, clientB)
      _ <- fundLNNodes(bitcoind, clientC, clientC)

      _ <- TestAsyncUtil.awaitConditionF(() => isSynced)
      _ <- TestAsyncUtil.awaitConditionF(() => isFunded)

      _ <- openChannel(bitcoind, clientA, clientB)
      _ <- openChannel(bitcoind, clientB, clientC)
      _ <- TestAsyncUtil.awaitConditionF(() => isSynced)
      _ <- TestAsyncUtil.awaitConditionF(() => knowsChannel.recover(_ => false),
                                         1.seconds,
                                         50)
      _ <- TestAsyncUtil.nonBlockingSleep(10.seconds)
    } yield (clientA, clientB, clientC)
  }
}

object TestUtil extends TestUtil
