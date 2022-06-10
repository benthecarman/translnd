package com.translnd.htlc.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import scodec.bits.ByteVector

trait Mac32 {

  def mac(message: ByteVector): ByteVector

  def verify(mac: ByteVector, message: ByteVector): Boolean

}

case class Hmac256(key: ByteVector) extends Mac32 {

  override def mac(message: ByteVector): ByteVector =
    Mac32.hmac256(key, message)

  override def verify(mac: ByteVector, message: ByteVector): Boolean =
    this.mac(message) === mac

}

object Mac32 {

  def hmac256(key: ByteVector, message: ByteVector): ByteVector = {
    val mac = new HMac(new SHA256Digest())
    mac.init(new KeyParameter(key.toArray))
    mac.update(message.toArray, 0, message.length.toInt)
    val output = new Array[Byte](32)
    mac.doFinal(output, 0)
    ByteVector.view(output)
  }
}
