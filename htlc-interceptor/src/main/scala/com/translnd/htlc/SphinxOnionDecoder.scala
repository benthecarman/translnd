package com.translnd.htlc

import org.bitcoins.crypto._
import scodec.bits.ByteVector

case class OnionRoutingPacket(
    version: Int,
    pubkey: ECPublicKey,
    payload: ByteVector,
    hmac: ByteVector)

object SphinxOnionDecoder {

  def decode(bytes: ByteVector): OnionRoutingPacket = {
    require(bytes.size == 1366)
    val version = bytes.head
    val pubkey = ECPublicKey(bytes.tail.take(33))
    val remaining = bytes.drop(34)
    val payloads = remaining.dropRight(32)
    val hmac = remaining.takeRight(32)

    OnionRoutingPacket(version, pubkey, payloads, hmac)
  }
}
