package com.translnd.htlc

import com.translnd.testkit.TripleLndFixture
import lnrpc.Invoice.InvoiceState
import lnrpc.SendRequest
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.number.UInt32
import org.bitcoins.lnd.rpc.LndUtils
import org.bitcoins.testkit.async.TestAsyncUtil

import scala.concurrent.duration.DurationInt

class HTLCInterceptorTest extends TripleLndFixture with LndUtils {

  it must "get info from all lnds" in { param =>
    val (_, lndA, htlc, lndC) = param
    val lndB = htlc.lnds.head

    for {
      infoA <- lndA.getInfo
      infoB <- lndB.getInfo
      infoC <- lndC.getInfo
    } yield {
      assert(infoA.identityPubkey != infoB.identityPubkey)
      assert(infoA.identityPubkey != infoC.identityPubkey)
      assert(infoB.identityPubkey != infoC.identityPubkey)

      assert(infoA.blockHeight >= UInt32.zero)
      assert(infoB.blockHeight >= UInt32.zero)
      assert(infoC.blockHeight >= UInt32.zero)
    }
  }

  it must "rotate keys" in { param =>
    val (_, _, htlc, _) = param

    val amount = Satoshis(1000)

    for {
      inv <- htlc.createInvoice("hello world", amount, 3600)
      inv2 <- htlc.createInvoice("hello world", amount, 3600)
    } yield assert(inv.nodeId != inv2.nodeId)
  }

  it must "make an uninterrupted routed payment" in { param =>
    val (_, lndA, _, lndC) = param

    for {
      inv <- lndC.addInvoice("hello world", Satoshis(100), 3600)

      pay <- lndA.lnd.sendPaymentSync(
        SendRequest(paymentRequest = inv.invoice.toString))

      inv <- lndC.lookupInvoice(inv.rHash)
    } yield {
      assert(pay.paymentError.isEmpty)
      assert(inv.amtPaidSat == 100)
      assert(inv.state == InvoiceState.SETTLED)
    }
  }

  it must "receive a payment" in { param =>
    val (_, lndA, htlc, _) = param

    val amount = Satoshis(100)

    val lnd = htlc.lnds.head

    for {
      preBal <- lnd.channelBalance()
      inv <- htlc.createInvoice("hello world", amount, 3600)

      pay <- lndA.lnd.sendPaymentSync(
        SendRequest(paymentRequest = inv.toString))
      _ <- TestAsyncUtil.nonBlockingSleep(5.seconds)

      invOpt <- htlc.lookupInvoice(inv.lnTags.paymentHash.hash)
      postBal <- lnd.channelBalance()
    } yield {
      assert(pay.paymentError.isEmpty)
      assert(preBal.localBalance + amount == postBal.localBalance)
      invOpt match {
        case Some(invoice) =>
          assert(invoice.settled)
        case None => fail("Invoice does not exist")
      }
    }
  }

  it must "not allow an expired payment" in { param =>
    val (_, lndA, htlc, _) = param

    val amount = Satoshis(100)

    for {
      inv <- htlc.createInvoice("hello world", amount, 1)
      _ <- TestAsyncUtil.nonBlockingSleep(5.seconds)

      res <- recoverToSucceededIf[Exception](
        lndA.lnd.sendPaymentSync(SendRequest(paymentRequest = inv.toString)))
    } yield res
  }
}
