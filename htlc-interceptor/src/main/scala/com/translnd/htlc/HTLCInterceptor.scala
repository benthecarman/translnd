package com.translnd.htlc

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import com.translnd.htlc.config._
import com.translnd.htlc.crypto.Sphinx
import com.translnd.htlc.db._
import grizzled.slf4j.Logging
import org.bitcoins.core.config._
import org.bitcoins.core.currency._
import org.bitcoins.core.number._
import org.bitcoins.core.protocol.ln.LnTag._
import org.bitcoins.core.protocol.ln._
import org.bitcoins.core.protocol.ln.currency._
import org.bitcoins.core.protocol.ln.fee._
import org.bitcoins.core.protocol.ln.node._
import org.bitcoins.core.protocol.ln.routing._
import org.bitcoins.crypto._
import org.bitcoins.lnd.rpc._
import routerrpc.ResolveHoldForwardAction._
import routerrpc._
import scodec.bits._

import scala.concurrent._
import scala.util._

class HTLCInterceptor(val lnds: Vector[LndRpcClient])(implicit
    conf: TransLndAppConfig,
    system: ActorSystem)
    extends LndUtils
    with Logging {
  import system.dispatcher

  private[this] val keyManager = new TransKeyManager()

  private[htlc] val invoiceDAO = InvoiceDAO()
  private[htlc] val channelIdDAO = ChannelIdDAO()

  private lazy val (queue, source) =
    Source
      .queue[ForwardHtlcInterceptResponse](200, OverflowStrategy.dropHead)
      .toMat(BroadcastHub.sink)(Keep.both)
      .run()

  private lazy val networkF: Future[BitcoinNetwork] = lnds.head.getInfo
    .map(
      _.chains.headOption
        .flatMap(c => BitcoinNetworks.fromStringOpt(c.network))
        .getOrElse(throw new IllegalArgumentException("Unknown LND network")))

  private lazy val nodeIdsF: Future[Vector[NodeId]] =
    Future.sequence(lnds.map(_.nodeId))

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
      nodeIds <- nodeIdsF
    } yield (network, nodeIds)

    dataF.flatMap { case (network, nodeIds) =>
      val (priv, idx) = keyManager.nextKey()
      val hrp = LnHumanReadablePart(network, amount)
      val preImage = ECPrivateKey.freshPrivateKey.bytes
      val hash = CryptoUtil.sha256(preImage)
      val paymentSecret = ECPrivateKey.freshPrivateKey.bytes

      val hashTag = PaymentHashTag(hash)
      val memoTag = DescriptionTag(memo)
      val expiryTimeTag = ExpiryTimeTag(UInt32(expiry))
      val paymentSecretTag = SecretTag(PaymentSecret(paymentSecret))
      val featuresTag = FeaturesTag(hex"2420") // copied from a LND invoice

      val routes = nodeIds.map { nodeId =>
        val chanId = ExistingChannelIds.getChannelId(amount.toSatoshis)
        LnRoute(
          pubkey = nodeId.pubKey,
          shortChannelID = chanId,
          feeBaseMsat = FeeBaseMSat(MilliSatoshis.zero),
          feePropMilli = FeeProportionalMillionths(UInt32.zero),
          cltvExpiryDelta = 40
        )
      }
      val routingInfo = RoutingInfo(routes)

      val tags = LnTaggedFields(
        Vector(hashTag,
               memoTag,
               expiryTimeTag,
               paymentSecretTag,
               featuresTag,
               routingInfo))

      val invoice: LnInvoice = LnInvoice.build(hrp, tags, priv)

      val invoiceDb = InvoiceDbs.fromLnInvoice(preImage, idx, invoice)
      val channelIdDbs = ChannelIdDbs(hash, routes.map(_.shortChannelID))

      for {
        invoiceDb <- invoiceDAO.create(invoiceDb)
        _ <- channelIdDAO.createAll(channelIdDbs)
      } yield invoiceDb.invoice
    }
  }

  def lookupInvoice(hash: Sha256Digest): Future[Option[InvoiceDb]] = {
    invoiceDAO.read(hash)
  }

  def readHTLC(
      hash: Sha256Digest): Future[Option[(InvoiceDb, Vector[ChannelIdDb])]] = {
    invoiceDAO.read(hash).flatMap {
      case None => Future.successful(None)
      case Some(invoiceDb) =>
        channelIdDAO.findByHash(hash).map { ids =>
          Some((invoiceDb, ids))
        }
    }
  }

  def startHTLCInterceptors(): Unit = {
    val _ = lnds.map(_.router
      .htlcInterceptor(source)
      .mapAsync(1) { request =>
        val hash = Sha256Digest(request.paymentHash)
        readHTLC(hash).flatMap {
          case None =>
            // Not our invoice, pass it along
            val resp =
              ForwardHtlcInterceptResponse(request.incomingCircuitKey,
                                           ResolveHoldForwardAction.RESUME)

            queue.offer(resp)
          case Some((db, scids)) =>
            val priv = keyManager.getKey(db.index)
            val onionT = SphinxOnionDecoder.decodeT(request.onionBlob)

            val decryptedT = onionT.flatMap(Sphinx.peel(priv, Some(hash), _))

            val (resp, updatedDb) = decryptedT match {
              case Failure(err) =>
                err.printStackTrace()
                val resp =
                  ForwardHtlcInterceptResponse(incomingCircuitKey =
                                                 request.incomingCircuitKey,
                                               action = FAIL)
                (resp, db)
              case Success(decrypted) =>
                if (!decrypted.isLastPacket) {
                  println("DID NOT GET LAST PACKET")
                }

                val action =
                  db.getAction(request,
                               decrypted.finalHopTLVStream,
                               scids.map(_.scid))

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

                (resp, updatedDb)
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
      .runWith(Sink.ignore))
  }
}
