package com.translnd.rotator.db

import com.translnd.rotator.InvoiceState
import com.translnd.rotator.InvoiceState._
import com.translnd.sphinx.FinalHopTLVStream
import lnrpc.Failure.FailureCode
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
      scids: Vector[ShortChannelId]): Option[
    (ResolveHoldForwardAction, Option[FailureCode])] = {
    if (Sha256Digest(req.paymentHash) != hash)
      throw new IllegalArgumentException("Payment Hash does not match")

    state match {
      case Paid =>
        Some((SETTLE, None)) // skip already settled invoices
      case Expired | Cancelled =>
        Some((FAIL, Some(FailureCode.INCORRECT_OR_UNKNOWN_PAYMENT_DETAILS)))
      case Accepted => None
      case Unpaid =>
        val now = TimeUtil.currentEpochSecond
        val wholeAmount = msats.toUInt64 <= req.outgoingAmountMsat &&
          finalHop.amtToForward.amt >= msats

        val correctAmt = finalHop.paymentDataOpt.exists(_.msats >= msats)

        val correctChanId =
          scids.map(_.u64).contains(req.outgoingRequestedChanId)

        val correctSecret =
          finalHop.paymentDataOpt.exists(_.paymentSecret == paymentSecret)

        val expired = expireTimeOpt.exists(_ < now)

        if (!correctAmt) {
          Some((FAIL, Some(FailureCode.FINAL_INCORRECT_HTLC_AMOUNT)))
        } else if (!correctSecret) {
          Some((FAIL, Some(FailureCode.INCORRECT_OR_UNKNOWN_PAYMENT_DETAILS)))
        } else if (!correctChanId) {
          Some((FAIL, Some(FailureCode.INCORRECT_OR_UNKNOWN_PAYMENT_DETAILS)))
        } else if (expired) {
          Some((FAIL, Some(FailureCode.INCORRECT_OR_UNKNOWN_PAYMENT_DETAILS)))
        } else if (wholeAmount) {
          Some((SETTLE, None))
        } else None
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
