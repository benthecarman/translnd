package com.translnd.htlc.db

import org.bitcoins.core.protocol.ln._
import org.bitcoins.core.protocol.ln.currency._
import org.bitcoins.crypto._
import org.bitcoins.lnd.rpc.LndUtils._
import routerrpc.ResolveHoldForwardAction._
import routerrpc._
import scodec.bits.ByteVector

case class InvoiceDb(
    hash: Sha256Digest,
    preimage: ByteVector,
    paymentSecret: PaymentSecret,
    amountOpt: Option[MilliSatoshis],
    invoice: LnInvoice,
    settled: Boolean) {
  lazy val msats: MilliSatoshis = amountOpt.getOrElse(MilliSatoshis.zero)

  def getAction(req: ForwardHtlcInterceptRequest): ResolveHoldForwardAction = {
    if (Sha256Digest(req.paymentHash) != hash)
      throw new IllegalArgumentException("Payment Hash does not match")

    if (settled) {
      SETTLE // skip already settled invoices
    } else {
      val validAmount = msats.toUInt64 <= req.outgoingAmountMsat

      if (validAmount) {
        SETTLE
      } else FAIL
    }
  }
}

object InvoiceDbs {

  def fromLnInvoice(preimage: ByteVector, invoice: LnInvoice): InvoiceDb = {
    val amountOpt = invoice.amount.map(_.toMSat)
    val secret = invoice.lnTags.secret
      .map(_.secret)
      .getOrElse(throw new IllegalArgumentException(
        "Invoice must have a payment secret"))

    InvoiceDb(invoice.lnTags.paymentHash.hash,
              preimage = preimage,
              paymentSecret = secret,
              amountOpt = amountOpt,
              invoice = invoice,
              settled = false)
  }
}
