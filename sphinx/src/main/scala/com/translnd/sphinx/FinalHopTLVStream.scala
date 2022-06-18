package com.translnd.sphinx

import org.bitcoins.core.protocol.tlv._

case class FinalHopTLVStream(
    amtToForward: AmtToForwardTLV,
    outgoingCLTVValue: OutgoingCLTVValueTLV,
    paymentDataOpt: Option[PaymentDataTLV])

object FinalHopTLVStream {

  def fromTLVs(vec: Vector[TLV]): FinalHopTLVStream = {
    val amtToForward = vec.collectFirst { case t: AmtToForwardTLV => t }
    val outgoing = vec.collectFirst { case t: OutgoingCLTVValueTLV => t }
    val paymentData = vec.collectFirst { case t: PaymentDataTLV => t }
    FinalHopTLVStream(amtToForward.get, outgoing.get, paymentData)
  }
}
