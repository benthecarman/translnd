package com.translnd.htlc.db

import com.translnd.htlc.FinalHopTLVStream
import org.bitcoins.core.protocol.ln._
import org.bitcoins.core.protocol.ln.channel.ShortChannelId
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
    chanId: ShortChannelId,
    index: Int,
    settled: Boolean) {
  lazy val msats: MilliSatoshis = amountOpt.getOrElse(MilliSatoshis.zero)

  def getAction(
      req: ForwardHtlcInterceptRequest,
      finalHop: FinalHopTLVStream): ResolveHoldForwardAction = {
    if (Sha256Digest(req.paymentHash) != hash)
      throw new IllegalArgumentException("Payment Hash does not match")

    if (settled) {
      SETTLE // skip already settled invoices
    } else {
      val validAmount =
        msats.toUInt64 <= req.outgoingAmountMsat &&
          finalHop.paymentDataOpt.exists(_.msats >= msats) &&
          finalHop.amtToForward.amt >= msats

      val correctChanId = req.outgoingRequestedChanId == chanId.u64

      val correctSecret =
        finalHop.paymentDataOpt.exists(_.paymentSecret == paymentSecret)

      if (validAmount && correctSecret && correctChanId) {
        SETTLE
      } else FAIL
    }
  }
}

object InvoiceDbs {

  def fromLnInvoice(
      preimage: ByteVector,
      idx: Int,
      chanId: ShortChannelId,
      invoice: LnInvoice): InvoiceDb = {
    val amountOpt = invoice.amount.map(_.toMSat)
    val secret = invoice.lnTags.secret
      .map(_.secret)
      .getOrElse(throw new IllegalArgumentException(
        "Invoice must have a payment secret"))

    InvoiceDb(invoice.lnTags.paymentHash.hash,
              preimage = preimage,
              index = idx,
              paymentSecret = secret,
              amountOpt = amountOpt,
              invoice = invoice,
              chanId = chanId,
              settled = false)
  }
}
