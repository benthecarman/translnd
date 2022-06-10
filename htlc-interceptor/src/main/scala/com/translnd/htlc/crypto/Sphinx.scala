package com.translnd.htlc.crypto

import com.translnd.htlc.{FinalHopTLVStream, OnionRoutingPacket}
import grizzled.slf4j.Logging
import org.bitcoins.core.protocol._
import org.bitcoins.core.protocol.tlv.TLV
import org.bitcoins.crypto._
import scodec.bits._

import scala.annotation.tailrec
import scala.util._

object Sphinx extends Logging {

  /** Supported packet version. Note that since this value is outside of the onion encrypted payload, intermediate
    * nodes may or may not use this value when forwarding the packet to the next node.
    */
  val Version = 0

  // We use HMAC-SHA256 which returns 32-bytes message authentication codes.
  val MacLength = 32

  def mac(key: ByteVector, message: ByteVector): Sha256Digest =
    Sha256Digest(Mac32.hmac256(key, message))

  def generateKey(keyType: ByteVector, secret: ByteVector): ByteVector =
    Mac32.hmac256(keyType, secret)

  def generateKey(keyType: String, secret: ByteVector): ByteVector =
    generateKey(ByteVector.view(keyType.getBytes("UTF-8")), secret)

  def zeroes(length: Int): ByteVector = ByteVector.low(length)

  def generateStream(key: ByteVector, length: Int): ByteVector =
    ChaCha20.encrypt(zeroes(length), key, zeroes(12))

  def computeSharedSecret(
      pub: ECPublicKey,
      secret: ECPrivateKey): ByteVector = {
    CryptoUtil
      .sha256(CryptoUtil.tweakMultiply(pub, secret.fieldElement).bytes)
      .bytes
  }

  def computeBlindingFactor(pub: ECPublicKey, secret: ByteVector): ByteVector =
    CryptoUtil.sha256(pub.bytes ++ secret).bytes

  def blind(pub: ECPublicKey, blindingFactor: ByteVector): ECPublicKey =
    CryptoUtil.tweakMultiply(pub, FieldElement.fromBytes(blindingFactor))

  def blind(pub: ECPublicKey, blindingFactors: Seq[ByteVector]): ECPublicKey =
    blindingFactors.foldLeft(pub)(blind)

  /** Compute the ephemeral public keys and shared secrets for all nodes on the route.
    *
    * @param sessionKey this node's session key.
    * @param ECPublicKeys public keys of each node on the route.
    * @return a tuple (ephemeral public keys, shared secrets).
    */
  def computeEphemeralECPublicKeysAndSharedSecrets(
      sessionKey: ECPrivateKey,
      ECPublicKeys: Seq[ECPublicKey]): (Seq[ECPublicKey], Seq[ByteVector]) = {
    val g = ECPublicKey(
      "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
    val ephemeralECPublicKey0 = blind(g, sessionKey.bytes)
    val secret0 = computeSharedSecret(ECPublicKeys.head, sessionKey)
    val blindingFactor0 = computeBlindingFactor(ephemeralECPublicKey0, secret0)
    computeEphemeralECPublicKeysAndSharedSecrets(sessionKey,
                                                 ECPublicKeys.tail,
                                                 Seq(ephemeralECPublicKey0),
                                                 Seq(blindingFactor0),
                                                 Seq(secret0))
  }

  @tailrec
  private def computeEphemeralECPublicKeysAndSharedSecrets(
      sessionKey: ECPrivateKey,
      ECPublicKeys: Seq[ECPublicKey],
      ephemeralECPublicKeys: Seq[ECPublicKey],
      blindingFactors: Seq[ByteVector],
      sharedSecrets: Seq[ByteVector]): (Seq[ECPublicKey], Seq[ByteVector]) = {
    if (ECPublicKeys.isEmpty)
      (ephemeralECPublicKeys, sharedSecrets)
    else {
      val ephemeralECPublicKey =
        blind(ephemeralECPublicKeys.last, blindingFactors.last)
      val secret =
        computeSharedSecret(blind(ECPublicKeys.head, blindingFactors),
                            sessionKey)
      val blindingFactor = computeBlindingFactor(ephemeralECPublicKey, secret)
      computeEphemeralECPublicKeysAndSharedSecrets(
        sessionKey,
        ECPublicKeys.tail,
        ephemeralECPublicKeys :+ ephemeralECPublicKey,
        blindingFactors :+ blindingFactor,
        sharedSecrets :+ secret)
    }
  }

  /** Peek at the first bytes of the per-hop payload to extract its length.
    */
  def peekPayloadLength(payload: ByteVector): Int = {
    payload.head match {
      case 0 =>
        65
      case _ =>
        val bigSize = BigSizeUInt.fromBytes(payload)
        val remaining = payload.drop(bigSize.byteSize)
        val sub = payload.size - remaining.size

        val total = bigSize.toInt + sub

        MacLength + total.toInt
    }
  }

  /** Decrypting an onion packet yields a payload for the current node and the encrypted packet for the next node.
    *
    * @param payload      decrypted payload for this node.
    * @param nextPacket   packet for the next node.
    * @param sharedSecret shared secret for the sending node, which we will need to return failure messages.
    */
  case class DecryptedPacket(
      payload: ByteVector,
      nextPacket: OnionRoutingPacket,
      sharedSecret: ByteVector) {
    val isLastPacket: Boolean = nextPacket.hmac == Sha256Digest.empty

    lazy val tlvStream: Vector[TLV] = {
      val totalSize = BigSizeUInt.fromBytes(payload)
      val remain = payload.drop(totalSize.byteSize)
      require(totalSize.toInt == remain.size)

      @tailrec
      def loop(accum: Vector[TLV], bytes: ByteVector): Vector[TLV] = {
        if (bytes.isEmpty) accum
        else {
          val tlv = TLV.fromBytes(bytes)
          val remaining = bytes.drop(tlv.byteSize)
          loop(accum :+ tlv, remaining)
        }
      }

      loop(Vector.empty, remain)
    }

    lazy val finalHopTLVStream: FinalHopTLVStream = {
      FinalHopTLVStream.fromTLVs(tlvStream)
    }
  }

  /** A encrypted onion packet with all the associated shared secrets.
    *
    * @param packet        encrypted onion packet.
    * @param sharedSecrets shared secrets (one per node in the route). Known (and needed) only if you're creating the
    *                      packet. Empty if you're just forwarding the packet to the next node.
    */
  case class PacketAndSecrets(
      packet: OnionRoutingPacket,
      sharedSecrets: Seq[(ByteVector, ECPublicKey)])

  /** Generate a deterministic filler to prevent intermediate nodes from knowing their position in the route.
    * See https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#filler-generation
    *
    * @param keyType             type of key used (depends on the onion we're building).
    * @param packetPayloadLength length of the packet's encrypted onion payload (e.g. 1300 for standard payment onions).
    * @param sharedSecrets       shared secrets for all the hops.
    * @param payloads            payloads for all the hops.
    * @return filler bytes.
    */
  def generateFiller(
      keyType: String,
      packetPayloadLength: Int,
      sharedSecrets: Seq[ByteVector],
      payloads: Seq[ByteVector]): ByteVector = {
    require(sharedSecrets.length == payloads.length,
            "the number of secrets should equal the number of payloads")

    (sharedSecrets zip payloads).foldLeft(ByteVector.empty)(
      (padding, secretAndPayload) => {
        val (secret, perHopPayload) = secretAndPayload
        val perHopPayloadLength = peekPayloadLength(perHopPayload)
        require(
          perHopPayloadLength == perHopPayload.length + MacLength,
          s"invalid payload: length isn't correctly encoded: $perHopPayloadLength != ${perHopPayload.length} + $MacLength"
        )
        val key = generateKey(keyType, secret)
        val padding1 = padding ++ ByteVector.low(perHopPayloadLength)
        val stream =
          generateStream(key, packetPayloadLength + perHopPayloadLength)
            .takeRight(padding1.length)
        padding1.xor(stream)
      })
  }

  /** Decrypt the incoming packet, extract the per-hop payload and build the packet for the next node.
    *
    * @param privateKey     this node's private key.
    * @param packet         packet received by this node.
    * @return a DecryptedPacket(payload, packet, shared secret) object where:
    *         - payload is the per-hop payload for this node.
    *         - packet is the next packet, to be forwarded using the info that is given in the payload.
    *         - shared secret is the secret we share with the node that sent the packet. We need it to propagate
    *           failure messages upstream.
    *           or a BadOnion error containing the hash of the invalid onion.
    */
  def peel(
      privateKey: ECPrivateKey,
      paymentHash: Option[Sha256Digest],
      packet: OnionRoutingPacket): Try[DecryptedPacket] =
    packet.version match {
      case 0 =>
        Try(packet.pubkey) match {
          case Success(packetEphKey) =>
            val sharedSecret = computeSharedSecret(packetEphKey, privateKey)
            val mu = generateKey("mu", sharedSecret)
            val hashBytes = paymentHash.map(_.bytes).getOrElse(ByteVector.empty)
            val msg = packet.payload ++ hashBytes
            val check = mac(mu, msg)
            if (check == packet.hmac) {
              val rho = generateKey("rho", sharedSecret)
              // Since we don't know the length of the per-hop payload (we will learn it once we decode the first bytes),
              // we have to pessimistically generate a long cipher stream.
              val stream = generateStream(rho, 2 * packet.payload.length.toInt)
              val bin = (packet.payload ++ ByteVector.low(
                packet.payload.length)) xor stream

              val perHopPayloadLength = peekPayloadLength(bin)
              val perHopPayload = bin.take(perHopPayloadLength - MacLength)

              val hmac =
                bin.slice(perHopPayloadLength - MacLength, perHopPayloadLength)
              val nextOnionPayload =
                bin.drop(perHopPayloadLength).take(packet.payload.length)
              val nextPubKey = blind(
                packetEphKey,
                computeBlindingFactor(packetEphKey, sharedSecret))

              Try(
                DecryptedPacket(perHopPayload,
                                OnionRoutingPacket(Version,
                                                   nextPubKey,
                                                   nextOnionPayload,
                                                   Sha256Digest(hmac)),
                                sharedSecret))
            } else {
              Failure(
                new RuntimeException(
                  s"Invalid onion hmac: ${check.hex} != ${packet.hmac.hex}"))
            }
          case Failure(_) => Failure(new RuntimeException("Invalid onion key"))
        }
      case _ => Failure(new RuntimeException("Unknown version"))
    }

  /** Wrap the given packet in an additional layer of onion encryption, adding an encrypted payload for a specific
    * node.
    *
    * Packets are constructed in reverse order:
    * - you first create the packet for the final recipient
    * - then you call wrap(...) until you've built the final onion packet that will be sent to the first node in the
    * route
    *
    * @param payload            per-hop payload for the target node.
    * @param associatedData     associated data.
    * @param ephemeralECPublicKey ephemeral key shared with the target node.
    * @param sharedSecret       shared secret with this hop.
    * @param packet             current packet or random bytes if the packet hasn't been initialized.
    * @param onionPayloadFiller optional onion payload filler, needed only when you're constructing the last packet.
    * @return the next packet.
    */
  def wrap(
      payload: ByteVector,
      associatedData: Option[ByteVector],
      ephemeralECPublicKey: ECPublicKey,
      sharedSecret: ByteVector,
      packet: Either[ByteVector, OnionRoutingPacket],
      onionPayloadFiller: ByteVector = ByteVector.empty): OnionRoutingPacket = {
    val packetPayloadLength = packet match {
      case Left(startingBytes) => startingBytes.length.toInt
      case Right(p)            => p.payload.length.toInt
    }
    require(
      payload.length <= packetPayloadLength - MacLength,
      s"packet per-hop payload cannot exceed ${packetPayloadLength - MacLength} bytes")

    val (currentMac, currentPayload): (Sha256Digest, ByteVector) =
      packet match {
        // Packet construction starts with an empty mac and random payload.
        case Left(startingBytes) => (Sha256Digest.empty, startingBytes)
        case Right(p)            => (p.hmac, p.payload)
      }
    val nextOnionPayload = {
      val onionPayload1 =
        payload ++ currentMac.bytes ++ currentPayload.dropRight(
          payload.length + MacLength)
      val onionPayload2 =
        onionPayload1 xor generateStream(generateKey("rho", sharedSecret),
                                         packetPayloadLength)
      onionPayload2.dropRight(onionPayloadFiller.length) ++ onionPayloadFiller
    }

    val nextHmac = mac(
      generateKey("mu", sharedSecret),
      associatedData.map(nextOnionPayload ++ _).getOrElse(nextOnionPayload))
    val nextPacket = OnionRoutingPacket(Version,
                                        ephemeralECPublicKey,
                                        nextOnionPayload,
                                        nextHmac)
    nextPacket
  }

  /** Create an encrypted onion packet that contains payloads for all nodes in the list.
    *
    * @param sessionKey          session key.
    * @param packetPayloadLength length of the packet's encrypted onion payload (e.g. 1300 for standard payment onions).
    * @param ECPublicKeys          node public keys (one per node).
    * @param payloads            payloads (one per node).
    * @param associatedData      associated data.
    * @return An onion packet with all shared secrets. The onion packet can be sent to the first node in the list, and
    *         the shared secrets (one per node) can be used to parse returned failure messages if needed.
    */
  def create(
      sessionKey: ECPrivateKey,
      packetPayloadLength: Int,
      ECPublicKeys: Seq[ECPublicKey],
      payloads: Seq[ByteVector],
      associatedData: Option[ByteVector]): PacketAndSecrets = {
    require(payloads.map(_.length + MacLength).sum <= packetPayloadLength,
            s"packet per-hop payloads cannot exceed $packetPayloadLength bytes")
    val (ephemeralECPublicKeys, sharedsecrets) =
      computeEphemeralECPublicKeysAndSharedSecrets(sessionKey, ECPublicKeys)
    val filler = generateFiller("rho",
                                packetPayloadLength,
                                sharedsecrets.init,
                                payloads.init)

    // We deterministically-derive the initial payload bytes: see https://github.com/lightningnetwork/lightning-rfc/pull/697
    val startingBytes =
      generateStream(generateKey("pad", sessionKey.bytes), packetPayloadLength)
    val lastPacket = wrap(payloads.last,
                          associatedData,
                          ephemeralECPublicKeys.last,
                          sharedsecrets.last,
                          Left(startingBytes),
                          filler)

    @tailrec
    def loop(
        hopPayloads: Seq[ByteVector],
        ephKeys: Seq[ECPublicKey],
        sharedSecrets: Seq[ByteVector],
        packet: OnionRoutingPacket): OnionRoutingPacket = {
      if (hopPayloads.isEmpty) packet
      else {
        val nextPacket = wrap(hopPayloads.last,
                              associatedData,
                              ephKeys.last,
                              sharedSecrets.last,
                              Right(packet))
        loop(hopPayloads.dropRight(1),
             ephKeys.dropRight(1),
             sharedSecrets.dropRight(1),
             nextPacket)
      }
    }

    val packet = loop(payloads.dropRight(1),
                      ephemeralECPublicKeys.dropRight(1),
                      sharedsecrets.dropRight(1),
                      lastPacket)
    PacketAndSecrets(packet, sharedsecrets.zip(ECPublicKeys))
  }
}
