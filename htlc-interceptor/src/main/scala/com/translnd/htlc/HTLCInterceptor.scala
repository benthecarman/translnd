package com.translnd.htlc

import akka.Done
import akka.stream._
import akka.stream.scaladsl._
import com.translnd.htlc.config._
import com.translnd.htlc.db._
import grizzled.slf4j.Logging
import lnrpc._
import org.bitcoins.core.config._
import org.bitcoins.core.currency._
import org.bitcoins.core.number._
import org.bitcoins.core.protocol.ln.LnTag._
import org.bitcoins.core.protocol.ln._
import org.bitcoins.core.protocol.ln.channel._
import org.bitcoins.core.protocol.ln.currency._
import org.bitcoins.core.protocol.ln.fee._
import org.bitcoins.core.protocol.ln.node._
import org.bitcoins.core.protocol.ln.routing._
import org.bitcoins.core.util.TimeUtil
import org.bitcoins.crypto._
import org.bitcoins.lnd.rpc._
import routerrpc.ResolveHoldForwardAction._
import routerrpc._
import scodec.bits._

import scala.concurrent._
import scala.util.Random

class HTLCInterceptor(val lnd: LndRpcClient)(implicit conf: TransLndAppConfig)
    extends LndUtils
    with Logging {
  import lnd.{executionContext, system}

  private[htlc] val invoiceDAO = InvoiceDAO()

  private val random = new Random(TimeUtil.currentEpochMs)

  def genKeys: (ECPrivateKey, ECPublicKey) = {
    val privateKey = ECPrivateKey.freshPrivateKey

    (privateKey, privateKey.publicKey)
  }

  private lazy val (queue, source) =
    Source
      .queue[ForwardHtlcInterceptResponse](200, OverflowStrategy.dropHead)
      .toMat(BroadcastHub.sink)(Keep.both)
      .run()

  private lazy val networkF: Future[BitcoinNetwork] = lnd.getInfo
    .map(
      _.chains.headOption
        .flatMap(c => BitcoinNetworks.fromStringOpt(c.network))
        .getOrElse(throw new IllegalArgumentException("Unknown LND network")))

  private lazy val nodeIdF: Future[NodeId] = lnd.nodeId

  def channelRoutes(): Future[Vector[LnRoute]] = {
    lnd.listChannels(ListChannelsRequest()).flatMap { channels =>
      val fs = channels.map { chan =>
        lnd.lnd.getChanInfo(ChanInfoRequest(chan.chanId)).map { chanInfo =>
          val policy =
            if (chanInfo.node1Pub == chan.remotePubkey) chanInfo.node2Policy.get
            else chanInfo.node1Policy.get

          val scid = ShortChannelId(chan.chanId)
          val base = FeeBaseMSat(MilliSatoshis(policy.feeBaseMsat))
          val feePropMilli =
            FeeProportionalMillionths(UInt32(policy.feeRateMilliMsat))
          val delta = policy.timeLockDelta.toInt.toShort

          LnRoute(ECPublicKey(chan.remotePubkey),
                  scid,
                  base,
                  feePropMilli,
                  delta)
        }
      }

      Future.sequence(fs)
    }
  }

  def createInvoice(
      memo: String,
      amount: CurrencyUnit,
      expiry: Long): Future[LnInvoice] = {
    val ln =
      LnCurrencyUnits.fromMSat(MilliSatoshis.fromSatoshis(amount.satoshis))

    createInvoice(memo, ln, expiry)
  }

  def createInvoice(
      memo: String,
      amount: LnCurrencyUnit,
      expiry: Long): Future[LnInvoice] = {
    val dataF = for {
      network <- networkF
      nodeId <- nodeIdF
      routeHints <- channelRoutes()
    } yield (network, nodeId, routeHints)

    dataF.flatMap { case (network, nodeId, _) =>
      val (priv, pub) = genKeys
      val hrp = LnHumanReadablePart(network, amount)
      val preImage = ECPrivateKey.freshPrivateKey.bytes
      val hash = CryptoUtil.sha256(preImage)
      val paymentSecret = ECPrivateKey.freshPrivateKey.bytes

      val hashTag = PaymentHashTag(hash)
      val memoTag = DescriptionTag(memo)
      val expiryTimeTag = ExpiryTimeTag(UInt32(expiry))
      val paymentSecretTag = SecretTag(PaymentSecret(paymentSecret))
      val featuresTag = FeaturesTag(hex"2420") // copied from a LND invoice

      // todo get scid from assumption set
      val scid = ShortChannelId(UInt64(1234567))
      val route = LnRoute(
        pubkey = nodeId.pubKey,
        shortChannelID = scid,
        feeBaseMsat = FeeBaseMSat(MilliSatoshis.zero),
        feePropMilli = FeeProportionalMillionths(UInt32.zero),
        cltvExpiryDelta = 40
      )

      val route2 = LnRoute(
        pubkey = pub,
        shortChannelID = scid,
        feeBaseMsat = FeeBaseMSat(MilliSatoshis.zero),
        feePropMilli = FeeProportionalMillionths(UInt32.zero),
        cltvExpiryDelta = 40
      )

      val routes = Vector(route, route2)
      val routingInfo = RoutingInfo(random.shuffle(routes))

      val tags = LnTaggedFields(
        Vector(hashTag,
               memoTag,
               expiryTimeTag,
               paymentSecretTag,
               featuresTag,
               routingInfo))

      val invoice: LnInvoice = LnInvoice.build(hrp, tags, priv)

      val invoiceDb = InvoiceDbs.fromLnInvoice(preImage, invoice)

      invoiceDAO.create(invoiceDb).map(_.invoice)
    }
  }

  def lookupInvoice(hash: Sha256Digest): Future[Option[InvoiceDb]] = {
    invoiceDAO.read(hash)
  }

  def startHTLCInterceptor(): Future[Done] = {
    lnd.router
      .htlcInterceptor(source)
      .mapAsync(1) { request =>
        val hash = Sha256Digest(request.paymentHash)
        invoiceDAO.read(hash).flatMap {
          case None =>
            // Not our invoice, pass it along
            val resp =
              ForwardHtlcInterceptResponse(request.incomingCircuitKey,
                                           ResolveHoldForwardAction.RESUME)

            queue.offer(resp)
          case Some(db) =>
            val action = db.getAction(request)

            val resp = action match {
              case SETTLE =>
                ForwardHtlcInterceptResponse(incomingCircuitKey =
                                               request.incomingCircuitKey,
                                             action = SETTLE,
                                             preimage = db.preimage)
              case action @ (FAIL | RESUME) =>
                ForwardHtlcInterceptResponse(incomingCircuitKey =
                                               request.incomingCircuitKey,
                                             action = action)
              case action: Unrecognized =>
                ForwardHtlcInterceptResponse(incomingCircuitKey =
                                               request.incomingCircuitKey,
                                             action = action,
                                             preimage = db.preimage)
            }

            val updatedDb = action match {
              case SETTLE          => db.copy(settled = true)
              case FAIL | RESUME   => db
              case _: Unrecognized => db
            }

            queue
              .offer(resp)
              .flatMap {
                case QueueOfferResult.Enqueued =>
                  invoiceDAO.update(updatedDb)
                case QueueOfferResult.Dropped =>
                  Future.failed(new RuntimeException(
                    s"Dropped response for invoice ${db.hash.hex}"))
                case QueueOfferResult.Failure(ex) =>
                  Future.failed(new RuntimeException(
                    s"Failed to respond for invoice ${db.hash.hex}: ${ex.getMessage}"))
                case QueueOfferResult.QueueClosed =>
                  Future.failed(new RuntimeException(
                    s"Queue closed: failed to respond for invoice ${db.hash.hex}"))
              }
        }
      }
      .runWith(Sink.ignore)
  }
}
