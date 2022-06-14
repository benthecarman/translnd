package com.translnd.htlc

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import com.google.common.util.concurrent.AtomicLongMap
import com.translnd.htlc.config._
import com.translnd.htlc.crypto.Sphinx
import com.translnd.htlc.db._
import grizzled.slf4j.Logging
import org.bitcoins.asyncutil.AsyncUtil
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
import slick.dbio.DBIO

import scala.concurrent._
import scala.concurrent.duration._
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
      expirySeconds: Long): Future[LnInvoice] = {
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
      val expiryTimeTag = ExpiryTimeTag(UInt32(expirySeconds))
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

      val action = for {
        invoiceDb <- invoiceDAO.createAction(invoiceDb)
        _ <- channelIdDAO.createAllAction(channelIdDbs)
      } yield invoiceDb.invoice

      invoiceDAO.safeDatabase.run(action)
    }
  }

  def lookupInvoice(hash: Sha256Digest): Future[Option[InvoiceDb]] = {
    invoiceDAO.read(hash)
  }

  def readHTLC(
      hash: Sha256Digest): Future[Option[(InvoiceDb, Vector[ChannelIdDb])]] = {
    val action = invoiceDAO.findByPrimaryKeyAction(hash).flatMap {
      case None => DBIO.successful(None)
      case Some(invoiceDb) =>
        channelIdDAO.findByHashAction(hash).map { ids =>
          Some((invoiceDb, ids))
        }
    }

    invoiceDAO.safeDatabase.run(action)
  }

  def startHTLCInterceptors(
      parallelism: Int = Runtime.getRuntime.availableProcessors()): Unit = {
    val _ = lnds.map { lnd =>
      val (queue, source) =
        Source
          .queue[ForwardHtlcInterceptResponse](bufferSize = 200,
                                               OverflowStrategy.dropHead,
                                               maxConcurrentOffers = 10)
          .toMat(BroadcastHub.sink)(Keep.both)
          .run()

      val paymentMap = AtomicLongMap.create[Sha256Digest]()

      lnd.router
        .htlcInterceptor(source)
        .mapAsync(parallelism) { request =>
          val ck = request.incomingCircuitKey
          val hash = Sha256Digest(request.paymentHash)
          readHTLC(hash).flatMap {
            case None =>
              // Not our invoice, pass it along
              val resp =
                ForwardHtlcInterceptResponse(request.incomingCircuitKey,
                                             ResolveHoldForwardAction.RESUME)

              queue.offer(resp).map(_ => ())
            case Some((db, scids)) =>
              val priv = keyManager.getKey(db.index)
              val onionT = SphinxOnionDecoder.decodeT(request.onionBlob)

              val decryptedT = onionT.flatMap(Sphinx.peel(priv, Some(hash), _))

              val resF = decryptedT match {
                case Failure(err) =>
                  err.printStackTrace()
                  val resp =
                    ForwardHtlcInterceptResponse(incomingCircuitKey = ck,
                                                 action = FAIL)
                  Future.successful((resp, db))
                case Success(decrypted) =>
                  val actionOpt = db.getAction(request,
                                               decrypted.finalHopTLVStream,
                                               scids.map(_.scid))

                  actionOpt match {
                    case Some(SETTLE) =>
                      paymentMap.remove(hash)
                      val resp =
                        ForwardHtlcInterceptResponse(incomingCircuitKey = ck,
                                                     action = SETTLE,
                                                     preimage = db.preimage)
                      Future.successful((resp, db.copy(settled = true)))
                    case Some(FAIL) =>
                      val resp =
                        ForwardHtlcInterceptResponse(incomingCircuitKey = ck,
                                                     action = FAIL)
                      Future.successful((resp, db))
                    case Some(RESUME) =>
                      val resp =
                        ForwardHtlcInterceptResponse(incomingCircuitKey = ck,
                                                     action = RESUME)
                      Future.successful((resp, db))
                    case Some(action: Unrecognized) =>
                      val resp =
                        ForwardHtlcInterceptResponse(incomingCircuitKey = ck,
                                                     action = action,
                                                     preimage = db.preimage)
                      Future.successful((resp, db))
                    case None =>
                      // Update paymentMap to have new payment
                      val amt = request.outgoingAmountMsat.toLong
                      paymentMap.addAndGet(hash, amt)

                      AsyncUtil
                        .awaitCondition(() => {
                                          val agg = paymentMap.get(hash)
                                          agg >= db.amountOpt
                                            .map(_.toLong)
                                            .getOrElse(0L)
                                        },
                                        interval = 100.milliseconds,
                                        maxTries = 1200 // 2 minutes
                        )
                        .map { _ =>
                          // Schedule in 10 seconds because we need to
                          // wait for other parts to finish
                          val runnable: Runnable = () => {
                            paymentMap.remove(hash)
                            ()
                          }
                          system.scheduler.scheduleOnce(10.seconds, runnable)

                          val resp =
                            ForwardHtlcInterceptResponse(ck,
                                                         action = SETTLE,
                                                         preimage = db.preimage)
                          (resp, db.copy(settled = true))
                        }
                        .recover { _ =>
                          paymentMap.remove(hash)
                          val resp =
                            ForwardHtlcInterceptResponse(ck, action = FAIL)
                          (resp, db)
                        }
                  }
              }

              for {
                (resp, updatedDb) <- resF
                res <- queue.offer(resp)
                _ <- res match {
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
              } yield ()
          }
        }
        .runWith(Sink.ignore)
    }
  }
}
