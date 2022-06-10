/*
 * CopySuccess 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.translnd.htlc.crypto

import com.translnd.htlc.OnionRoutingPacket
import org.bitcoins.crypto._
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

import scala.util.Success

/** Created by fabrice on 10/01/17.
  */
class SphinxSpec extends AnyFunSuite {

  import Sphinx._
  import SphinxSpec._

  test("generate ephemeral keys and secrets (reference test vector)") {
    val (ephkeys, sharedsecrets) =
      computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys)
    assert(ephkeys.head == ECPublicKey(
      hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"))
    assert(
      sharedsecrets.head == hex"53eb63ea8a3fec3b3cd433b85cd62a4b145e1dda09391b348c4e1cd36a03ea66")
    assert(ephkeys(1) == ECPublicKey(
      hex"028f9438bfbf7feac2e108d677e3a82da596be706cc1cf342b75c7b7e22bf4e6e2"))
    assert(sharedsecrets(
      1) == hex"a6519e98832a0b179f62123b3567c106db99ee37bef036e783263602f3488fae")
    assert(ephkeys(2) == ECPublicKey(
      hex"03bfd8225241ea71cd0843db7709f4c222f62ff2d4516fd38b39914ab6b83e0da0"))
    assert(sharedsecrets(
      2) == hex"3a6b412548762f0dbccce5c7ae7bb8147d1caf9b5471c34120b30bc9c04891cc")
    assert(ephkeys(3) == ECPublicKey(
      hex"031dde6926381289671300239ea8e57ffaf9bebd05b9a5b95beaf07af05cd43595"))
    assert(sharedsecrets(
      3) == hex"21e13c2d7cfe7e18836df50872466117a295783ab8aab0e7ecc8c725503ad02d")
    assert(ephkeys(4) == ECPublicKey(
      hex"03a214ebd875aab6ddfd77f22c5e7311d7f77f17a169e599f157bbcdae8bf071f4"))
    assert(sharedsecrets(
      4) == hex"b5756b9b542727dbafc6765a49488b023a725d631af688fc031217e90770c328")
  }

  test("generate filler with fixed-size payloads (reference test vector)") {
    val (_, sharedsecrets) =
      computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys)
    val filler = generateFiller("rho",
                                1300,
                                sharedsecrets.dropRight(1),
                                referenceFixedSizePaymentPayloads.dropRight(1))
    assert(
      filler == hex"c6b008cf6414ed6e4c42c291eb505e9f22f5fe7d0ecdd15a833f4d016ac974d33adc6ea3293e20859e87ebfb937ba406abd025d14af692b12e9c9c2adbe307a679779259676211c071e614fdb386d1ff02db223a5b2fae03df68d321c7b29f7c7240edd3fa1b7cb6903f89dc01abf41b2eb0b49b6b8d73bb0774b58204c0d0e96d3cce45ad75406be0bc009e327b3e712a4bd178609c00b41da2daf8a4b0e1319f07a492ab4efb056f0f599f75e6dc7e0d10ce1cf59088ab6e873de377343880f7a24f0e36731a0b72092f8d5bc8cd346762e93b2bf203d00264e4bc136fc142de8f7b69154deb05854ea88e2d7506222c95ba1aab065c8a851391377d3406a35a9af3ac")
  }

  test("generate filler with variable-size payloads") {
    val (_, sharedsecrets) =
      computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys)
    val filler =
      generateFiller("rho",
                     1300,
                     sharedsecrets.dropRight(1),
                     referenceVariableSizePaymentPayloads.dropRight(1))
    assert(
      filler == hex"b77d99c935d3f32469844f7e09340a91ded147557bdd0456c369f7e449587c0f5666faab58040146db49024db88553729bce12b860391c29c1779f022ae48a9cb314ca35d73fc91addc92632bcf7ba6fd9f38e6fd30fabcedbd5407b6648073c38331ee7ab0332f41f550c180e1601f8c25809ed75b3a1e78635a2ef1b828e92c9658e76e49f995d72cf9781eec0c838901d0bdde3ac21c13b4979ac9e738a1c4d0b9741d58e777ad1aed01263ad1390d36a18a6b92f4f799dcf75edbb43b7515e8d72cb4f827a9af0e7b9338d07b1a24e0305b5535f5b851b1144bad6238b9d9482b5ba6413f1aafac3cdde5067966ed8b78f7c1c5f916a05f874d5f17a2b7d0ae75d66a5f1bb6ff932570dc5a0cf3ce04eb5d26bc55c2057af1f8326e20a7d6f0ae644f09d00fac80de60f20aceee85be41a074d3e1dda017db79d0070b99f54736396f206ee3777abd4c00a4bb95c871750409261e3b01e59a3793a9c20159aae4988c68397a1443be6370fd9614e46108291e615691729faea58537209fa668a172d066d0efff9bc77c2bd34bd77870ad79effd80140990e36731a0b72092f8d5bc8cd346762e93b2bf203d00264e4bc136fc142de8f7b69154deb05854ea88e2d7506222c95ba1aab065c8a")
  }

  test("peek at per-hop payload length") {
    val testCases = Map(
      34 -> hex"01",
      41 -> hex"08",
      65 -> hex"00",
      285 -> hex"fc",
      288 -> hex"fd00fd",
      65570 -> hex"fdffff"
    )

    for ((expected, payload) <- testCases) {
      assert(peekPayloadLength(payload) === expected)
    }
  }

  val one =
    hex"0100000000000000000000000000000000000000000000000000000000000000"

  test("is last packet") {
    val testCases = Seq(
      // Bolt 1.0 payloads use the next packet's hmac to signal termination.
      (true,
       DecryptedPacket(hex"00",
                       OnionRoutingPacket(0,
                                          publicKeys.head,
                                          ByteVector.empty,
                                          Sha256Digest.empty),
                       one)),
      (false,
       DecryptedPacket(hex"00",
                       OnionRoutingPacket(0,
                                          publicKeys.head,
                                          ByteVector.empty,
                                          Sha256Digest(one)),
                       one)),
      // Bolt 1.1 payloads currently also use the next packet's hmac to signal termination.
      (true,
       DecryptedPacket(hex"0101",
                       OnionRoutingPacket(0,
                                          publicKeys.head,
                                          ByteVector.empty,
                                          Sha256Digest.empty),
                       one)),
      (false,
       DecryptedPacket(hex"0101",
                       OnionRoutingPacket(0,
                                          publicKeys.head,
                                          ByteVector.empty,
                                          Sha256Digest(one)),
                       one)),
      (false,
       DecryptedPacket(hex"0100",
                       OnionRoutingPacket(0,
                                          publicKeys.head,
                                          ByteVector.empty,
                                          Sha256Digest(one)),
                       one)),
      (false,
       DecryptedPacket(hex"0101",
                       OnionRoutingPacket(0,
                                          publicKeys.head,
                                          ByteVector.empty,
                                          Sha256Digest(one)),
                       one))
    )

    for ((expected, packet) <- testCases) {
      assert(packet.isLastPacket === expected)
    }
  }

  test(
    "create payment packet with fixed-size payloads (reference test vector)") {
    val PacketAndSecrets(onion, sharedSecrets) =
      create(sessionKey,
             1300,
             publicKeys,
             referenceFixedSizePaymentPayloads,
             associatedData.map(_.bytes))

    val Success(DecryptedPacket(payload0, nextPacket0, sharedSecret0)) =
      peel(privKeys.head, associatedData, onion)
    val Success(DecryptedPacket(payload1, nextPacket1, sharedSecret1)) =
      peel(privKeys(1), associatedData, nextPacket0)
    val Success(DecryptedPacket(payload2, nextPacket2, sharedSecret2)) =
      peel(privKeys(2), associatedData, nextPacket1)
    val Success(DecryptedPacket(payload3, nextPacket3, sharedSecret3)) =
      peel(privKeys(3), associatedData, nextPacket2)
    val Success(DecryptedPacket(payload4, nextPacket4, sharedSecret4)) =
      peel(privKeys(4), associatedData, nextPacket3)
    assert(
      Seq(payload0,
          payload1,
          payload2,
          payload3,
          payload4) == referenceFixedSizePaymentPayloads)
    assert(
      Seq(sharedSecret0,
          sharedSecret1,
          sharedSecret2,
          sharedSecret3,
          sharedSecret4) == sharedSecrets.map(_._1))

    val packets =
      Seq(nextPacket0, nextPacket1, nextPacket2, nextPacket3, nextPacket4)
    assert(packets.head.hmac ==
      Sha256Digest(
        hex"a93aa4f40241cef3e764e24b28570a0db39af82ab5102c3a04e51bec8cca9394"))
    assert(packets(1).hmac ==
      Sha256Digest(
        hex"5d1b11f1efeaa9be32eb1c74b113c0b46f056bb49e2a35a51ceaece6bd31332c"))
    assert(packets(2).hmac ==
      Sha256Digest(
        hex"19ca6357b5552b28e50ae226854eec874bbbf7025cf290a34c06b4eff5d2bac0"))
    assert(packets(3).hmac ==
      Sha256Digest(
        hex"16d4553c6084b369073d259381bb5b02c16bb2c590bbd9e69346cf7ebd563229"))
    // this means that node #4 is the last node
    assert(packets(4).hmac == Sha256Digest.empty)
  }

  test(
    "create payment packet with variable-size payloads (reference test vector)") {
    val PacketAndSecrets(onion, sharedSecrets) =
      create(sessionKey,
             1300,
             publicKeys,
             referenceVariableSizePaymentPayloads,
             associatedData.map(_.bytes))

    val Success(DecryptedPacket(payload0, nextPacket0, sharedSecret0)) =
      peel(privKeys.head, associatedData, onion)
    val Success(DecryptedPacket(payload1, nextPacket1, sharedSecret1)) =
      peel(privKeys(1), associatedData, nextPacket0)
    val Success(DecryptedPacket(payload2, nextPacket2, sharedSecret2)) =
      peel(privKeys(2), associatedData, nextPacket1)
    val Success(DecryptedPacket(payload3, nextPacket3, sharedSecret3)) =
      peel(privKeys(3), associatedData, nextPacket2)
    val Success(DecryptedPacket(payload4, nextPacket4, sharedSecret4)) =
      peel(privKeys(4), associatedData, nextPacket3)
    assert(
      Seq(payload0,
          payload1,
          payload2,
          payload3,
          payload4) == referenceVariableSizePaymentPayloads)
    assert(
      Seq(sharedSecret0,
          sharedSecret1,
          sharedSecret2,
          sharedSecret3,
          sharedSecret4) == sharedSecrets.map(_._1))

    val packets =
      Seq(nextPacket0, nextPacket1, nextPacket2, nextPacket3, nextPacket4)
    assert(packets.head.hmac ==
      Sha256Digest(
        hex"4ecb91c341543953a34d424b64c36a9cd8b4b04285b0c8de0acab0b6218697fc"))
    assert(packets(1).hmac ==
      Sha256Digest(
        hex"3d8e429a1e8d7bdb2813cd491f17771aa75670d88b299db1954aa015d035408f"))
    assert(packets(2).hmac ==
      Sha256Digest(
        hex"30ad58843d142609ed7ae2b960c8ce0e331f7d45c7d705f67fd3f3978cd7b8f8"))
    assert(packets(3).hmac ==
      Sha256Digest(
        hex"4ee0600ee609f1f3356b85b0af8ead34c2db4ae93e3978d15f983040e8b01acd"))
    assert(packets(4).hmac == Sha256Digest.empty)
  }

  test("create payment packet with variable-size payloads filling the onion") {
    val PacketAndSecrets(onion, sharedSecrets) =
      create(sessionKey,
             1300,
             publicKeys,
             variableSizePaymentPayloadsFull,
             associatedData.map(_.bytes))

    val Success(DecryptedPacket(payload0, nextPacket0, sharedSecret0)) =
      peel(privKeys.head, associatedData, onion)
    val Success(DecryptedPacket(payload1, nextPacket1, sharedSecret1)) =
      peel(privKeys(1), associatedData, nextPacket0)
    val Success(DecryptedPacket(payload2, nextPacket2, sharedSecret2)) =
      peel(privKeys(2), associatedData, nextPacket1)
    val Success(DecryptedPacket(payload3, nextPacket3, sharedSecret3)) =
      peel(privKeys(3), associatedData, nextPacket2)
    val Success(DecryptedPacket(payload4, nextPacket4, sharedSecret4)) =
      peel(privKeys(4), associatedData, nextPacket3)
    assert(
      Seq(payload0,
          payload1,
          payload2,
          payload3,
          payload4) == variableSizePaymentPayloadsFull)
    assert(
      Seq(sharedSecret0,
          sharedSecret1,
          sharedSecret2,
          sharedSecret3,
          sharedSecret4) == sharedSecrets.map(_._1))

    val packets =
      Seq(nextPacket0, nextPacket1, nextPacket2, nextPacket3, nextPacket4)
    assert(
      packets.head.hmac == Sha256Digest(
        hex"859cd694cf604442547246f4fae144f255e71e30cb366b9775f488cac713f0db"))
    assert(
      packets(1).hmac == Sha256Digest(
        hex"259982a8af80bd3b8018443997fa5f74c48b488fff62e531be54b887d53fe0ac"))
    assert(
      packets(2).hmac == Sha256Digest(
        hex"58110c95368305b73ae15d22b884fda0482c60993d3ba4e506e37ff5021efb13"))
    assert(
      packets(3).hmac == Sha256Digest(
        hex"f45e7099e32b8973f54cbfd1f6c48e7e0b90718ad7b00a88e1e98cebeb6d3916"))
    assert(packets(4).hmac == Sha256Digest.empty)
  }

  test(
    "create payment packet with single variable-size payload filling the onion") {
    val PacketAndSecrets(onion, _) = create(sessionKey,
                                            1300,
                                            publicKeys.take(1),
                                            variableSizeOneHopPaymentPayload,
                                            associatedData.map(_.bytes))

    val Success(DecryptedPacket(payload, nextPacket, _)) =
      peel(privKeys.head, associatedData, onion)
    assert(payload == variableSizeOneHopPaymentPayload.head)
    assert(nextPacket.hmac == Sha256Digest.empty)
  }

  test("create packet with invalid payload") {
    // In this test vector, the payload length (encoded as a varint in the first bytes) isn't equal to the actual
    // payload length.
    val incorrectVarint = Seq(
      hex"fd2a0101234567",
      hex"000000000000000000000000000000000000000000000000000000000000000000"
    )

    assertThrows[IllegalArgumentException](
      create(sessionKey,
             1300,
             publicKeys.take(2),
             incorrectVarint,
             associatedData.map(_.bytes)))
  }

  test("computeSharedSecret test 1") {
    val nodePrivateKey = ECPrivateKey(
      hex"4141414141414141414141414141414141414141414141414141414141414141")
    val nodeId = ECPublicKey(
      hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619")
    assert(nodePrivateKey.publicKey == nodeId)
    val blindingSecret = ECPrivateKey(
      hex"0505050505050505050505050505050505050505050505050505050505050505")
    val blindingKey = ECPublicKey(
      hex"0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f7")
    assert(blindingSecret.publicKey == blindingKey)
    val sharedSecret =
      hex"2e83e9bc7821d3f6cec7301fa8493aee407557624fb5745bede9084852430e3f"
    assert(Sphinx.computeSharedSecret(nodeId, blindingSecret) == sharedSecret)
    assert(
      Sphinx.computeSharedSecret(blindingKey, nodePrivateKey) == sharedSecret)
    assert(
      Sphinx.mac(ByteVector("blinded_node_id".getBytes),
                 sharedSecret) == Sha256Digest(
        hex"7d846b3445621d49a665e5698c52141e9dda8fa2fe0c3da7e0f9008ccc588a38"))
  }

  test("computeSharedSecret test 2") {
    val nodePrivateKey = ECPrivateKey(
      hex"4242424242424242424242424242424242424242424242424242424242424242")
    val nodeId = ECPublicKey(
      hex"0324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c")
    assert(nodePrivateKey.publicKey == nodeId)
    val blindingSecret = ECPrivateKey(
      hex"76d4de6c329c79623842dcf8f8eaee90c9742df1b5231f5350df4a231d16ebcf")
    val blindingKey = ECPublicKey(
      hex"03fc5e56da97b462744c9a6b0ba9d5b3ffbfb1a08367af9cc6ea5ae03c79a78eec")
    assert(blindingSecret.publicKey == blindingKey)
    val sharedSecret =
      hex"f18a1ddb1cb27d8fc4faf2cf317e87524fcc6b7f053496d95bf6e6809d09851e"
    assert(Sphinx.computeSharedSecret(nodeId, blindingSecret) == sharedSecret)
    assert(
      Sphinx.computeSharedSecret(blindingKey, nodePrivateKey) == sharedSecret)
    assert(
      Sphinx.mac(ByteVector("blinded_node_id".getBytes),
                 sharedSecret) == Sha256Digest(
        hex"8074773a3745818b0d97dd875023486cc35e7afd95f5e9ec1363f517979e8373"))
  }

  test("computeSharedSecret test 3") {
    val nodePrivateKey = ECPrivateKey.fromBytes(
      hex"4343434343434343434343434343434343434343434343434343434343434343")
    val nodeId = ECPublicKey(
      hex"027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007")
    assert(nodePrivateKey.publicKey == nodeId)
    val blindingSecret = ECPrivateKey(
      hex"0707070707070707070707070707070707070707070707070707070707070707")
    val blindingKey = ECPublicKey(
      hex"02989c0b76cb563971fdc9bef31ec06c3560f3249d6ee9e5d83c57625596e05f6f")
    assert(blindingSecret.publicKey == blindingKey)
    val sharedSecret =
      hex"8c0f7716da996c4913d720dbf691b559a4945bf70cdd18e0b61e3e42635efc9c"
    assert(Sphinx.computeSharedSecret(nodeId, blindingSecret) == sharedSecret)
    assert(
      Sphinx.computeSharedSecret(blindingKey, nodePrivateKey) == sharedSecret)
    assert(
      Sphinx.mac(ByteVector("blinded_node_id".getBytes),
                 sharedSecret) == Sha256Digest(
        hex"02afb2187075c8af51488242194b44c02624785ccd6fd43b5796c68f3025bf88"))
  }
}

object SphinxSpec {

  val privKeys: Seq[ECPrivateKey] = Seq(
    ECPrivateKey(
      hex"4141414141414141414141414141414141414141414141414141414141414141"),
    ECPrivateKey(
      hex"4242424242424242424242424242424242424242424242424242424242424242"),
    ECPrivateKey(
      hex"4343434343434343434343434343434343434343434343434343434343434343"),
    ECPrivateKey(
      hex"4444444444444444444444444444444444444444444444444444444444444444"),
    ECPrivateKey(
      hex"4545454545454545454545454545454545454545454545454545454545454545")
  )
  val publicKeys: Seq[ECPublicKey] = privKeys.map(_.publicKey)
  assert(
    publicKeys == Seq(
      ECPublicKey(
        hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"),
      ECPublicKey(
        hex"0324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c"),
      ECPublicKey(
        hex"027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007"),
      ECPublicKey(
        hex"032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991"),
      ECPublicKey(
        hex"02edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145")
    ))

  val sessionKey: ECPrivateKey = ECPrivateKey(
    hex"4141414141414141414141414141414141414141414141414141414141414141")

  // This test vector uses payloads with a fixed size.
  // origin -> node #0 -> node #1 -> node #2 -> node #3 -> node #4
  val referenceFixedSizePaymentPayloads: Seq[ByteVector] = Seq(
    hex"000000000000000000000000000000000000000000000000000000000000000000",
    hex"000101010101010101000000000000000100000001000000000000000000000000",
    hex"000202020202020202000000000000000200000002000000000000000000000000",
    hex"000303030303030303000000000000000300000003000000000000000000000000",
    hex"000404040404040404000000000000000400000004000000000000000000000000"
  )

  // This test vector uses variable-size payloads intertwined with fixed-size payloads.
  // origin -> node #0 -> node #1 -> node #2 -> node #3 -> node #4
  val referenceVariableSizePaymentPayloads: Seq[ByteVector] = Seq(
    hex"000000000000000000000000000000000000000000000000000000000000000000",
    hex"140101010101010101000000000000000100000001",
    hex"fd0100000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
    hex"140303030303030303000000000000000300000003",
    hex"000404040404040404000000000000000400000004000000000000000000000000"
  )

  // This test vector uses a single variable-sized payload filling the whole onion payload.
  // origin -> recipient
  val variableSizeOneHopPaymentPayload: Seq[ByteVector] = Seq(
    hex"fd04f16500000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
  )

  // This test vector uses variable-sized payloads and fills the whole onion packet.
  // origin -> node #0 -> node #1 -> node #2 -> node #3 -> node #4
  val variableSizePaymentPayloadsFull: Seq[ByteVector] = Seq(
    hex"8b09000000000000000030000000000000000000000000000000000000000000000000000000000025000000000000000000000000000000000000000000000000250000000000000000000000000000000000000000000000002500000000000000000000000000000000000000000000000025000000000000000000000000000000000000000000000000",
    hex"fd012a08000000000000009000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000200000000000000000000000000000000000000020000000000000000000000000000000000000002000000000000000000000000000000000000000200000000000000000000000000000000000000020000000000000000000000000000000000000002000000000000000000000000000000000000000200000000000000000000000000000000000000020000000000000000000000000000000000000002000000000000000000000000000000000000000",
    hex"620800000000000000900000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
    hex"fc120000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000",
    hex"fd01582200000000000000000000000000000000000000000022000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000"
  )

  val associatedData: Some[Sha256Digest] = Some(
    Sha256Digest(
      hex"4242424242424242424242424242424242424242424242424242424242424242"))
}
