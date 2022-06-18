package com.translnd.rotator.db

import com.translnd.rotator.{FinalHopTLVStream, InvoiceState}
import com.translnd.rotator.InvoiceState._
import org.bitcoins.core.protocol.ln._
import org.bitcoins.core.protocol.ln.channel.ShortChannelId
import org.bitcoins.core.protocol.ln.currency._
import org.bitcoins.core.util.TimeUtil
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
    amountPaidOpt: Option[MilliSatoshis],
    expireTimeOpt: Option[Long],
    invoice: LnInvoice,
    index: Int,
    state: InvoiceState) {

  lazy val msats: MilliSatoshis = amountOpt.getOrElse(MilliSatoshis.zero)

  /** Returns what action to */
  def getAction(
      req: ForwardHtlcInterceptRequest,
      finalHop: FinalHopTLVStream,
      scids: Vector[ShortChannelId]): Option[ResolveHoldForwardAction] = {
    if (Sha256Digest(req.paymentHash) != hash)
      throw new IllegalArgumentException("Payment Hash does not match")

    state match {
      case Paid =>
        Some(SETTLE) // skip already settled invoices
      case Expired | Cancelled => Some(FAIL)
      case Accepted            => None
      case Unpaid =>
        val now = TimeUtil.currentEpochSecond
        val wholeAmount = msats.toUInt64 <= req.outgoingAmountMsat &&
          finalHop.amtToForward.amt >= msats

        val correctAmt = finalHop.paymentDataOpt.exists(_.msats >= msats)

        val correctChanId =
          scids.map(_.u64).contains(req.outgoingRequestedChanId)

        val correctSecret =
          finalHop.paymentDataOpt.exists(_.paymentSecret == paymentSecret)

        val notExpired = expireTimeOpt.forall(_ >= now)

        if (correctAmt && correctSecret && correctChanId && notExpired) {
          if (wholeAmount) Some(SETTLE)
          else None
        } else Some(FAIL)
    }
  }
}

object InvoiceDbs {

  def fromLnInvoice(
      preimage: ByteVector,
      idx: Int,
      invoice: LnInvoice): InvoiceDb = {
    val amountOpt = invoice.amount.map(_.toMSat)
    val secret = invoice.lnTags.secret
      .map(_.secret)
      .getOrElse(throw new IllegalArgumentException(
        "Invoice must have a payment secret"))

    val expireTimeOpt = invoice.lnTags.expiryTime.map { t =>
      TimeUtil.currentEpochSecond + t.u32.toLong
    }

    InvoiceDb(
      invoice.lnTags.paymentHash.hash,
      preimage = preimage,
      index = idx,
      paymentSecret = secret,
      amountOpt = amountOpt,
      amountPaidOpt = None,
      expireTimeOpt = expireTimeOpt,
      invoice = invoice,
      state = Unpaid
    )
  }
}
