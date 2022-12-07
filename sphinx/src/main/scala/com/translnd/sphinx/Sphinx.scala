package com.translnd.sphinx

import org.bitcoins.core.number._
import org.bitcoins.core.protocol.BigSizeUInt
import org.bitcoins.core.protocol.ln.currency.MilliSatoshis
import org.bitcoins.core.protocol.tlv.TLV
import org.bitcoins.crypto._
import scodec.bits._

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

object Sphinx {

  /** Supported packet version. Note that since this value is outside of the
    * onion encrypted payload, intermediate nodes may or may not use this value
    * when forwarding the packet to the next node.
    */
  val Version = 0

  // We use HMAC-SHA256 which returns 32-bytes message authentication codes.
  val MacLength = 32

  def mac(key: ByteVector, message: ByteVector): Sha256Digest =
    Sha256Digest(CryptoUtil.hmac256(key, message))

  def generateKey(keyType: ByteVector, secret: ECPrivateKey): ByteVector =
    CryptoUtil.hmac256(keyType, secret.bytes)

  def generateKey(keyType: String, secret: ECPrivateKey): ByteVector =
    generateKey(ByteVector.view(keyType.getBytes("UTF-8")), secret)

  def zeroes(length: Int): ByteVector = ByteVector.low(length)

  def generateStream(key: ByteVector, length: Int): ByteVector =
    ChaCha20.encrypt(zeroes(length), key, zeroes(12))

  def computeSharedSecret(
      pub: ECPublicKey,
      secret: ECPrivateKey): ECPrivateKey = ECPrivateKey {
    CryptoUtil
      .sha256(pub.multiply(secret.fieldElement).bytes)
      .bytes
  }

  def computeBlindingFactor(
      pub: ECPublicKey,
      secret: ECPrivateKey): ByteVector =
    CryptoUtil.sha256(pub.bytes ++ secret.bytes).bytes

  def blind(pub: ECPublicKey, blindingFactor: ByteVector): ECPublicKey = {
    pub.multiply(FieldElement.fromBytes(blindingFactor))
  }

  def blind(
      pub: ECPublicKey,
      blindingFactors: Vector[ByteVector]): ECPublicKey =
    blindingFactors.foldLeft(pub)(blind)

  /** Compute the ephemeral public keys and shared secrets for all nodes on the
    * route.
    *
    * @param sessionKey
    *   this node's session key.
    * @param pubKeys
    *   public keys of each node on the route.
    * @return
    *   a tuple (ephemeral public keys, shared secrets).
    */
  def computeEphemeralECPublicKeysAndSharedSecrets(
      sessionKey: ECPrivateKey,
      pubKeys: Vector[ECPublicKey]): (Vector[ECPublicKey],
                                      Vector[ECPrivateKey]) = {
    val ephemeralECPublicKey0 = blind(CryptoParams.getG, sessionKey.bytes)
    val secret0 = computeSharedSecret(pubKeys.head, sessionKey)
    val blindingFactor0 = computeBlindingFactor(ephemeralECPublicKey0, secret0)
    computeEphemeralECPublicKeysAndSharedSecrets(sessionKey,
                                                 pubKeys.tail,
                                                 Vector(ephemeralECPublicKey0),
                                                 Vector(blindingFactor0),
                                                 Vector(secret0))
  }

  @tailrec
  private def computeEphemeralECPublicKeysAndSharedSecrets(
      sessionKey: ECPrivateKey,
      pubKeys: Vector[ECPublicKey],
      ephemeralECPublicKeys: Vector[ECPublicKey],
      blindingFactors: Vector[ByteVector],
      sharedSecrets: Vector[ECPrivateKey]): (Vector[ECPublicKey],
                                             Vector[ECPrivateKey]) = {
    if (pubKeys.isEmpty)
      (ephemeralECPublicKeys, sharedSecrets)
    else {
      val ephemeralECPublicKey =
        blind(ephemeralECPublicKeys.last, blindingFactors.last)
      val secret =
        computeSharedSecret(blind(pubKeys.head, blindingFactors), sessionKey)
      val blindingFactor =
        computeBlindingFactor(ephemeralECPublicKey, secret)
      computeEphemeralECPublicKeysAndSharedSecrets(
        sessionKey,
        pubKeys.tail,
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

  /** Decrypting an onion packet yields a payload for the current node and the
    * encrypted packet for the next node.
    *
    * @param payload
    *   decrypted payload for this node.
    * @param nextPacket
    *   packet for the next node.
    * @param sharedSecret
    *   shared secret for the sending node, which we will need to return failure
    *   messages.
    */
  case class DecryptedPacket(
      payload: ByteVector,
      nextPacket: OnionRoutingPacket,
      sharedSecret: ECPrivateKey) {
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
    * @param packet
    *   encrypted onion packet.
    * @param sharedSecrets
    *   shared secrets (one per node in the route). Known (and needed) only if
    *   you're creating the packet. Empty if you're just forwarding the packet
    *   to the next node.
    */
  case class PacketAndSecrets(
      packet: OnionRoutingPacket,
      sharedSecrets: Vector[(ECPrivateKey, ECPublicKey)])

  /** Generate a deterministic filler to prevent intermediate nodes from knowing
    * their position in the route. See
    * https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#filler-generation
    *
    * @param keyType
    *   type of key used (depends on the onion we're building).
    * @param packetPayloadLength
    *   length of the packet's encrypted onion payload (e.g. 1300 for standard
    *   payment onions).
    * @param sharedSecrets
    *   shared secrets for all the hops.
    * @param payloads
    *   payloads for all the hops.
    * @return
    *   filler bytes.
    */
  def generateFiller(
      keyType: String,
      packetPayloadLength: Int,
      sharedSecrets: Vector[ECPrivateKey],
      payloads: Vector[ByteVector]): ByteVector = {
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

  /** Decrypt the incoming packet, extract the per-hop payload and build the
    * packet for the next node.
    *
    * @param privateKey
    *   this node's private key.
    * @param packet
    *   packet received by this node.
    * @return
    *   a DecryptedPacket(payload, packet, shared secret) object where:
    *   - payload is the per-hop payload for this node.
    *   - packet is the next packet, to be forwarded using the info that is
    *     given in the payload.
    *   - shared secret is the secret we share with the node that sent the
    *     packet. We need it to propagate failure messages upstream. or a
    *     BadOnion error containing the hash of the invalid onion.
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

  /** Wrap the given packet in an additional layer of onion encryption, adding
    * an encrypted payload for a specific node.
    *
    * Packets are constructed in reverse order:
    *   - you first create the packet for the final recipient
    *   - then you call wrap(...) until you've built the final onion packet that
    *     will be sent to the first node in the route
    *
    * @param payload
    *   per-hop payload for the target node.
    * @param associatedData
    *   associated data.
    * @param ephemeralECPublicKey
    *   ephemeral key shared with the target node.
    * @param sharedSecret
    *   shared secret with this hop.
    * @param packet
    *   current packet or random bytes if the packet hasn't been initialized.
    * @param onionPayloadFiller
    *   optional onion payload filler, needed only when you're constructing the
    *   last packet.
    * @return
    *   the next packet.
    */
  def wrap(
      payload: ByteVector,
      associatedData: Option[ByteVector],
      ephemeralECPublicKey: ECPublicKey,
      sharedSecret: ECPrivateKey,
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

  def computeEphemeralPublicKeysAndSharedSecrets(
      sessionKey: ECPrivateKey,
      publicKeys: Vector[ECPublicKey]): (Vector[ECPublicKey],
                                         Vector[ECPrivateKey]) = {
    val ephemeralPublicKey0 = blind(CryptoParams.getG, sessionKey.bytes)
    val secret0 = computeSharedSecret(publicKeys.head, sessionKey)
    val blindingFactor0 = computeBlindingFactor(ephemeralPublicKey0, secret0)
    computeEphemeralPublicKeysAndSharedSecrets(sessionKey,
                                               publicKeys.tail,
                                               Vector(ephemeralPublicKey0),
                                               Vector(blindingFactor0),
                                               Vector(secret0))
  }

  @tailrec
  private def computeEphemeralPublicKeysAndSharedSecrets(
      sessionKey: ECPrivateKey,
      publicKeys: Vector[ECPublicKey],
      ephemeralPublicKeys: Vector[ECPublicKey],
      blindingFactors: Vector[ByteVector],
      sharedSecrets: Vector[ECPrivateKey]): (Vector[ECPublicKey],
                                             Vector[ECPrivateKey]) = {
    if (publicKeys.isEmpty)
      (ephemeralPublicKeys, sharedSecrets)
    else {
      val ephemeralPublicKey =
        blind(ephemeralPublicKeys.last, blindingFactors.last)
      val secret =
        computeSharedSecret(blind(publicKeys.head, blindingFactors), sessionKey)
      val blindingFactor = computeBlindingFactor(ephemeralPublicKey, secret)
      computeEphemeralPublicKeysAndSharedSecrets(
        sessionKey,
        publicKeys.tail,
        ephemeralPublicKeys :+ ephemeralPublicKey,
        blindingFactors :+ blindingFactor,
        sharedSecrets :+ secret)
    }
  }

  /** Create an encrypted onion packet that contains payloads for all nodes in
    * the list.
    *
    * @param sessionKey
    *   session key.
    * @param packetPayloadLength
    *   length of the packet's encrypted onion payload (e.g. 1300 for standard
    *   payment onions).
    * @param pubKeys
    *   node public keys (one per node).
    * @param payloads
    *   payloads (one per node).
    * @param associatedData
    *   associated data.
    * @return
    *   An onion packet with all shared secrets. The onion packet can be sent to
    *   the first node in the list, and the shared secrets (one per node) can be
    *   used to parse returned failure messages if needed.
    */
  def create(
      sessionKey: ECPrivateKey,
      packetPayloadLength: Int,
      pubKeys: Vector[ECPublicKey],
      payloads: Vector[ByteVector],
      associatedData: Option[ByteVector]): PacketAndSecrets = {
    require(payloads.map(_.length + MacLength).sum <= packetPayloadLength,
            s"packet per-hop payloads cannot exceed $packetPayloadLength bytes")
    val (ephemeralECPublicKeys, sharedSecrets) =
      computeEphemeralECPublicKeysAndSharedSecrets(sessionKey, pubKeys)
    val filler = generateFiller("rho",
                                packetPayloadLength,
                                sharedSecrets.dropRight(1),
                                payloads.dropRight(1))

    // We deterministically-derive the initial payload bytes: see https://github.com/lightningnetwork/lightning-rfc/pull/697
    val startingBytes =
      generateStream(generateKey("pad", sessionKey), packetPayloadLength)
    val lastPacket = wrap(payloads.last,
                          associatedData,
                          ephemeralECPublicKeys.last,
                          sharedSecrets.last,
                          Left(startingBytes),
                          filler)

    @tailrec
    def loop(
        hopPayloads: Vector[ByteVector],
        ephKeys: Vector[ECPublicKey],
        sharedSecrets: Vector[ECPrivateKey],
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
                      sharedSecrets.dropRight(1),
                      lastPacket)
    PacketAndSecrets(packet, sharedSecrets.zip(pubKeys))
  }

  object FailurePacket {

    val MaxPayloadLength = 256
    val PacketLength: Int = MacLength + MaxPayloadLength + 2 + 2

    /** Create a failure packet that will be returned to the sender. Each
      * intermediate hop will add a layer of encryption and forward to the
      * previous hop. Note that malicious intermediate hops may drop the packet
      * or alter it (which breaks the mac).
      *
      * @param sharedSecret
      *   destination node's shared secret that was computed when the original
      *   onion for the HTLC was created or forwarded: see OnionPacket.create()
      *   and OnionPacket.wrap().
      * @return
      *   a failure packet that can be sent to the destination node.
      */
    def incorrectOrUnknownPaymentDetails(
        sharedSecret: ECPrivateKey,
        msat: MilliSatoshis,
        height: Int): ByteVector = {
      val um = generateKey("um", sharedSecret)
      val failureMsg = hex"400f" ++ msat.toUInt64.bytes ++ UInt32(height).bytes
      val mac = CryptoUtil.hmac256(um, failureMsg)
      val padLen = MaxPayloadLength - failureMsg.length
      require(padLen >= 0, s"failure message is too long: ${failureMsg.length}")
      val packet =
        mac ++ UInt16(failureMsg.length).bytes ++ failureMsg ++ UInt16(
          padLen).bytes ++ ByteVector.low(padLen)
      wrap(packet, sharedSecret)
    }

    /** Wrap the given packet in an additional layer of onion encryption for the
      * previous hop.
      *
      * @param packet
      *   failure packet.
      * @param sharedSecret
      *   destination node's shared secret.
      * @return
      *   an encrypted failure packet that can be sent to the destination node.
      */
    def wrap(packet: ByteVector, sharedSecret: ECPrivateKey): ByteVector = {
      val key = generateKey("ammag", sharedSecret)
      val stream = generateStream(key, PacketLength)
      // If we received a packet with an invalid length, we trim and pad to forward a packet with a normal length upstream.
      // This is a poor man's attempt at increasing the likelihood of the sender receiving the error.
      packet.take(PacketLength).padLeft(PacketLength) xor stream
    }

    // I deleted the decrypt method because it's not used in the codebase
  }
}
