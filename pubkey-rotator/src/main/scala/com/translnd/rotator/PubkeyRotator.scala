package com.translnd.rotator

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl._
import com.google.common.util.concurrent.AtomicLongMap
import com.translnd.channel.ids.ExistingChannelId
import com.translnd.rotator.InvoiceState._
import com.translnd.rotator.config._
import com.translnd.rotator.db._
import com.translnd.sphinx._
import grizzled.slf4j.Logging
import lnrpc.Failure.FailureCode
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

class PubkeyRotator private (val lnds: Vector[LndRpcClient])(implicit
    val config: TransLndAppConfig,
    system: ActorSystem)
    extends LndUtils
    with Logging {
  import system.dispatcher

  require(lnds.nonEmpty, "Lnds cannot be empty")

  private[this] val keyManager = new TransKeyManager()

  private[rotator] val invoiceDAO = InvoiceDAO()
  private[rotator] val channelIdDAO = ChannelIdDAO()

  private lazy val networkF: Future[BitcoinNetwork] = lnds.head.getInfo
    .map(
      _.chains.headOption
        .flatMap(c => BitcoinNetworks.fromStringOpt(c.network))
        .getOrElse(throw new IllegalArgumentException("Unknown LND network")))

  private lazy val nodeIdsF: Future[Vector[NodeId]] =
    Future.sequence(lnds.map(_.nodeId))

  def createInvoice(
      descHash: Sha256Digest,
      amount: CurrencyUnit,
      expiry: Long): Future[LnInvoice] = {
    val ln =
      LnCurrencyUnits.fromMSat(MilliSatoshis.fromSatoshis(amount.satoshis))

    createInvoice(None, Some(descHash), ln, expiry)
  }

  def createInvoice(
      descHash: Sha256Digest,
      amount: MilliSatoshis,
      expiry: Long): Future[LnInvoice] = {
    val ln = LnCurrencyUnits.fromMSat(amount)

    createInvoice(None, Some(descHash), ln, expiry)
  }

  def createInvoice(
      memo: String,
      amount: CurrencyUnit,
      expiry: Long): Future[LnInvoice] = {
    val ln =
      LnCurrencyUnits.fromMSat(MilliSatoshis.fromSatoshis(amount.satoshis))

    createInvoice(Some(memo), None, ln, expiry)
  }

  def createInvoice(
      memo: String,
      amount: MilliSatoshis,
      expiry: Long): Future[LnInvoice] = {
    val ln = LnCurrencyUnits.fromMSat(amount)

    createInvoice(Some(memo), None, ln, expiry)
  }

  def createInvoice(
      memoOpt: Option[String],
      descHashOpt: Option[Sha256Digest],
      amount: LnCurrencyUnit,
      expirySeconds: Long): Future[LnInvoice] = {
    require(
      (memoOpt.nonEmpty && memoOpt.get.length < 640) ||
        descHashOpt.nonEmpty,
      "You must supply either a description hash, or a literal description that is 640 characters or less to create an invoice."
    )
    require(!(memoOpt.nonEmpty && descHashOpt.nonEmpty),
            "Cannot have both description and description hash")

    val dataF = for {
      network <- networkF
      nodeIds <- nodeIdsF
    } yield (network, nodeIds)

    dataF.flatMap { case (network, nodeIds) =>
      val (priv, idx) = keyManager.nextKey()
      val hrp = LnHumanReadablePart(network, amount)
      val preImage = CryptoUtil.randomBytes(32)
      val hash = CryptoUtil.sha256(preImage)
      val paymentSecret = CryptoUtil.randomBytes(32)

      val hashTag = PaymentHashTag(hash)
      val memoTagOpt = memoOpt.map(DescriptionTag)
      val descHashTagOpt = descHashOpt.map(DescriptionHashTag)
      val expiryTimeTag = ExpiryTimeTag(UInt32(expirySeconds))
      val paymentSecretTag = SecretTag(PaymentSecret(paymentSecret))
      val featuresTag = FeaturesTag(hex"2420") // copied from a LND invoice

      val routes = nodeIds.map { nodeId =>
        val chanId = ExistingChannelId.getChannelId(amount.toSatoshis)
        LnRoute(
          pubkey = nodeId.pubKey,
          shortChannelID = chanId,
          feeBaseMsat = FeeBaseMSat(MilliSatoshis.zero),
          feePropMilli = FeeProportionalMillionths(UInt32.zero),
          cltvExpiryDelta = 40
        )
      }
      val routingInfo = RoutingInfo(routes)

      val memoTag = memoTagOpt.orElse(descHashTagOpt).get

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
      } yield invoiceDb

      for {
        invoiceDb <- invoiceDAO.safeDatabase.run(action)
        _ <- invoiceQueue.offer(invoiceDb)
      } yield invoiceDb.invoice
    }
  }

  def lookupInvoice(hash: Sha256Digest): Future[Option[InvoiceDb]] = {
    invoiceDAO.read(hash)
  }

  def cancelInvoice(hash: Sha256Digest): Future[Option[InvoiceDb]] = {
    val action = invoiceDAO.findByPrimaryKeyAction(hash).flatMap {
      case None => DBIO.successful(None)
      case Some(db) =>
        val updated = db.state match {
          case Paid =>
            throw new RuntimeException(
              s"Cannot cancel an invoice that has been paid")
          case Cancelled | Expired => db
          case Unpaid | Accepted   => db.copy(state = Cancelled)
        }

        invoiceDAO.updateAction(updated).map(Some(_))
    }

    for {
      res <- invoiceDAO.safeDatabase.run(action)
      _ <- res.map(invoiceQueue.offer).getOrElse(Future.unit)
    } yield res
  }

  private[this] val (invoiceQueue, invoiceSource) =
    Source
      .queue[InvoiceDb](bufferSize = 200,
                        OverflowStrategy.dropHead,
                        maxConcurrentOffers = 10)
      .toMat(BroadcastHub.sink)(Keep.both)
      .run()

  def subscribeInvoices(): Source[InvoiceDb, NotUsed] = {
    invoiceSource
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

  private def start(): PubkeyRotator = {
    val parallelism = Runtime.getRuntime.availableProcessors()
    val _ = lnds.map { lnd =>
      val (queue, source) =
        Source
          .queue[ForwardHtlcInterceptResponse](bufferSize = 200,
                                               OverflowStrategy.backpressure,
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
                    ForwardHtlcInterceptResponse(
                      incomingCircuitKey = ck,
                      action = FAIL,
                      failureCode = FailureCode.INVALID_ONION_HMAC)
                  Future.successful((resp, db))
                case Success(decrypted) =>
                  val actionOpt = db.getAction(request,
                                               decrypted.finalHopTLVStream,
                                               scids.map(_.scid))

                  actionOpt match {
                    case Some((SETTLE, _)) =>
                      val resp =
                        ForwardHtlcInterceptResponse(incomingCircuitKey = ck,
                                                     action = SETTLE,
                                                     preimage = db.preimage)
                      val amtPaid =
                        MilliSatoshis(request.outgoingAmountMsat.toBigInt)
                      val updatedDb =
                        db.copy(state = Paid, amountPaidOpt = Some(amtPaid))
                      handleOnInvoicePaid(updatedDb, resp)
                      Future.successful((resp, updatedDb))
                    case Some((FAIL, errOpt)) =>
                      val resp =
                        ForwardHtlcInterceptResponse(
                          incomingCircuitKey = ck,
                          action = FAIL,
                          failureCode = errOpt.getOrElse(FailureCode.RESERVED))
                      Future.successful((resp, db))
                    case Some((RESUME, _)) =>
                      val resp =
                        ForwardHtlcInterceptResponse(incomingCircuitKey = ck,
                                                     action = RESUME)
                      Future.successful((resp, db))
                    case Some((action: Unrecognized, errOpt)) =>
                      val resp =
                        ForwardHtlcInterceptResponse(
                          incomingCircuitKey = ck,
                          action = action,
                          preimage = db.preimage,
                          failureCode = errOpt.getOrElse(FailureCode.RESERVED))
                      Future.successful((resp, db))
                    case None =>
                      // Update paymentMap to have new payment
                      val amt = request.outgoingAmountMsat.toLong
                      paymentMap.addAndGet(hash, amt)

                      AsyncUtil
                        .awaitCondition(
                          () => {
                            val agg = paymentMap.get(hash)
                            agg >= db.amountOpt.map(_.toLong).getOrElse(0L)
                          },
                          interval = 100.milliseconds,
                          maxTries = 1200 // 2 minutes
                        )
                        .map { _ =>
                          val resp =
                            ForwardHtlcInterceptResponse(ck,
                                                         action = SETTLE,
                                                         preimage = db.preimage)

                          val amtPaid = MilliSatoshis(paymentMap.get(hash))
                          val updatedDb =
                            db.copy(state = Paid, amountPaidOpt = Some(amtPaid))

                          // Schedule for later because we need to
                          // wait for other parts to finish
                          val runnable: Runnable = () => {
                            // This will only return non-zero on the first
                            // call, so we can only call handleOnInvoicePaid once
                            val ret = paymentMap.remove(hash)
                            if (ret != 0) {
                              handleOnInvoicePaid(updatedDb, resp)
                            }
                            ()
                          }
                          system.scheduler.scheduleOnce(1.seconds, runnable)
                          (resp, updatedDb)
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
    // start expired checker
    system.scheduler.scheduleAtFixedRate(1.second, 1.second) { () =>
      val _ = invoiceDAO.markInvoicesExpired().map { dbs =>
        dbs.map(invoiceQueue.offer)
      }
    }
    this
  }

  private def handleOnInvoicePaid(
      db: InvoiceDb,
      response: ForwardHtlcInterceptResponse): Unit = {
    response.action match {
      case SETTLE =>
        invoiceQueue.offer(db)
        ()
      case Unrecognized(_) | RESUME | FAIL =>
        () // do nothing
    }
  }
}

object PubkeyRotator {

  def apply(lnds: Vector[LndRpcClient])(implicit
      conf: TransLndAppConfig,
      system: ActorSystem): PubkeyRotator = {
    new PubkeyRotator(lnds).start()
  }

  def apply(lnd: LndRpcClient)(implicit
      conf: TransLndAppConfig,
      system: ActorSystem): PubkeyRotator = {
    new PubkeyRotator(Vector(lnd)).start()
  }
}
