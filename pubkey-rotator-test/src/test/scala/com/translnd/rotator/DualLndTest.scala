package com.translnd.rotator

import akka.stream.scaladsl.Sink
import com.translnd.rotator.InvoiceState._
import com.translnd.testkit._
import lnrpc.SendRequest
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.ln.currency.MilliSatoshis
import org.bitcoins.lnd.rpc.LndUtils
import org.bitcoins.testkit.async.TestAsyncUtil
import routerrpc.SendPaymentRequest

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class DualLndTest extends DualLndFixture with LndUtils {

  it must "receive a mpp payment" in { param =>
    val (_, lndA, htlc) = param

    // larger than channel size so it needs to be multiple parts
    val amount: Satoshis = (CHANNEL_SIZE + Satoshis(1000)).satoshis

    val lnd = htlc.lnds.head

    for {
      preBal <- lnd.channelBalance()
      invoice <- htlc.createInvoice("hello world", amount, 3600)

      request = SendPaymentRequest(
        paymentRequest = invoice.toString,
        timeoutSeconds = 60,
        maxParts = UInt32(10),
        maxShardSizeMsat = MilliSatoshis.fromSatoshis(CHANNEL_SIZE).toUInt64,
        noInflightUpdates = true
      )
      pay <- lndA.sendPayment(request)
      _ <- TestAsyncUtil.nonBlockingSleep(5.seconds)

      invOpt <- htlc.lookupInvoice(invoice.lnTags.paymentHash.hash)
      postBal <- lnd.channelBalance()
    } yield {
      assert(pay.failureReason.isFailureReasonNone,
             s"Payment failure: ${pay.failureReason}")
      assert(preBal.localBalance + amount == postBal.localBalance)
      invOpt match {
        case Some(invoiceDb) =>
          assert(
            invoiceDb.amountPaidOpt.contains(
              MilliSatoshis.fromSatoshis(amount)))
          assert(invoiceDb.hash == invoice.lnTags.paymentHash.hash)
          assert(invoiceDb.state == Paid)
        case None => fail("Invoice does not exist")
      }
    }
  }

  it must "receive an invoice subscription from a mpp payment" in { param =>
    val (_, lndA, htlc) = param

    val amount = Satoshis(100)

    val lnd = htlc.lnds.head

    val dbF = htlc
      .subscribeInvoices()
      .filter(_.state == Paid)
      .runWith(Sink.head)

    for {
      preBal <- lnd.channelBalance()
      inv <- htlc.createInvoice("hello world", amount, 3600)

      request = SendPaymentRequest(
        paymentRequest = inv.toString,
        timeoutSeconds = 60,
        maxParts = UInt32(10),
        maxShardSizeMsat = MilliSatoshis.fromSatoshis(CHANNEL_SIZE).toUInt64,
        noInflightUpdates = true
      )
      pay <- lndA.sendPayment(request)
      _ <- TestAsyncUtil.nonBlockingSleep(5.seconds)

      invOpt <- htlc.lookupInvoice(inv.lnTags.paymentHash.hash)
      postBal <- lnd.channelBalance()
      db = Await.result(dbF, 10.seconds)
    } yield {
      assert(db.state == Paid)
      assert(db.hash == inv.lnTags.paymentHash.hash)
      assert(pay.failureReason.isFailureReasonNone)
      assert(preBal.localBalance + amount == postBal.localBalance)
      invOpt match {
        case Some(invoice) =>
          assert(db == invoice)
          assert(
            invoice.amountPaidOpt.contains(MilliSatoshis.fromSatoshis(amount)))
          assert(invoice.hash == inv.lnTags.paymentHash.hash)
          assert(invoice.state == Paid)
        case None => fail("Invoice does not exist")
      }
    }
  }

  it must "receive a payment" in { param =>
    val (_, lndA, htlc) = param

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
          assert(
            invoice.amountPaidOpt.contains(MilliSatoshis.fromSatoshis(amount)))
          assert(invoice.hash == inv.lnTags.paymentHash.hash)
          assert(invoice.state == Paid)
        case None => fail("Invoice does not exist")
      }
    }
  }

  it must "receive an invoice subscription" in { param =>
    val (_, lndA, htlc) = param

    val amount = Satoshis(100)

    val lnd = htlc.lnds.head

    val dbF = htlc
      .subscribeInvoices()
      .filter(_.state == Paid)
      .runWith(Sink.head)

    for {
      preBal <- lnd.channelBalance()
      inv <- htlc.createInvoice("hello world", amount, 3600)

      pay <- lndA.lnd.sendPaymentSync(
        SendRequest(paymentRequest = inv.toString))
      _ <- TestAsyncUtil.nonBlockingSleep(5.seconds)

      invOpt <- htlc.lookupInvoice(inv.lnTags.paymentHash.hash)
      postBal <- lnd.channelBalance()
      db = Await.result(dbF, 10.seconds)
    } yield {
      assert(db.state == Paid)
      assert(db.hash == inv.lnTags.paymentHash.hash)
      assert(pay.paymentError.isEmpty)
      assert(preBal.localBalance + amount == postBal.localBalance)
      invOpt match {
        case Some(invoice) =>
          assert(db == invoice)
          assert(
            invoice.amountPaidOpt.contains(MilliSatoshis.fromSatoshis(amount)))
          assert(invoice.hash == inv.lnTags.paymentHash.hash)
          assert(invoice.state == Paid)
        case None => fail("Invoice does not exist")
      }
    }
  }

  it must "not allow an expired payment" in { param =>
    val (_, lndA, htlc) = param

    val amount = Satoshis(100)

    val dbF = htlc
      .subscribeInvoices()
      .filter(_.state == Expired)
      .runWith(Sink.head)

    for {
      inv <- htlc.createInvoice("hello world", amount, 1)
      _ <- TestAsyncUtil.nonBlockingSleep(5.seconds)

      _ <- recoverToSucceededIf[Exception](
        lndA.lnd.sendPaymentSync(SendRequest(paymentRequest = inv.toString)))
      db = Await.result(dbF, 10.seconds)
    } yield {
      assert(db.state == Expired)
      assert(db.invoice == inv)
      assert(db.amountPaidOpt.isEmpty)
    }
  }
}
