package com.translnd.htlc

import com.translnd.testkit.TripleLndFixture
import lnrpc.Invoice.InvoiceState
import lnrpc.SendRequest
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.number.UInt32
import org.bitcoins.lnd.rpc.LndUtils

class TripleLndTest extends TripleLndFixture with LndUtils {

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
}
