package com.translnd.htlc

import org.bitcoins.crypto._
import scodec.bits.ByteVector

import scala.util.Try

case class OnionRoutingPacket(
    version: Int,
    pubkey: ECPublicKey,
    payload: ByteVector,
    hmac: Sha256Digest)

object SphinxOnionDecoder {

  def decodeT(bytes: ByteVector): Try[OnionRoutingPacket] = Try {
    require(bytes.size == 1366)
    val version = bytes.head
    val pubkey = ECPublicKey(bytes.tail.take(33))
    val remaining = bytes.drop(34)
    val payload = remaining.dropRight(32)
    val hmac = remaining.takeRight(32)

    OnionRoutingPacket(version, pubkey, payload, Sha256Digest(hmac))
  }
}
