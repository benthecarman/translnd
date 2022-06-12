package com.translnd.htlc

import com.translnd.testkit._
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.ln.currency.MilliSatoshis
import org.bitcoins.lnd.rpc.LndUtils
import routerrpc.SendPaymentRequest

class MultiPartPaymentTest extends MppLndFixture with LndUtils {

  it must "receive a mpp payment" in { param =>
    val (_, lndA, htlc) = param

    // larger than channel size so it needs to be multiple parts
    val amount: Satoshis = (CHANNEL_SIZE + Satoshis(1000)).satoshis

    htlc.startHTLCInterceptors()
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

      invOpt <- htlc.lookupInvoice(invoice.lnTags.paymentHash.hash)
      postBal <- lnd.channelBalance()
    } yield {
      assert(pay.failureReason.isFailureReasonNone)
      assert(preBal.localBalance + amount == postBal.localBalance)
      invOpt match {
        case Some(invoice) =>
          assert(invoice.settled)
        case None => fail("Invoice does not exist")
      }
    }
  }
}
