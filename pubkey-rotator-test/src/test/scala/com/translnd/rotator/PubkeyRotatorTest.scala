package com.translnd.rotator

import akka.stream.scaladsl.Sink
import com.translnd.rotator.InvoiceState._
import com.translnd.testkit.PubkeyRotatorFixture
import org.bitcoins.core.currency._
import org.bitcoins.core.protocol.ln.currency.MilliSatoshis
import org.bitcoins.crypto.CryptoUtil
import org.bitcoins.lnd.rpc.LndUtils

class PubkeyRotatorTest extends PubkeyRotatorFixture with LndUtils {

  override val pubkeyPrefixOpt: Option[String] = Some("02a")

  it must "create an invoice" in { param =>
    val (_, htlc) = param

    val amount = Satoshis(1000)
    val memo = "hello world"
    val expiry = 3600

    for {
      inv <- htlc.createInvoice(memo, amount, expiry)
      dbOpt <- htlc.lookupInvoice(inv.lnTags.paymentHash.hash)
    } yield {
      assert(inv.lnTags.description.exists(_.string == memo))
      assert(inv.amount.exists(_.toSatoshis == amount))
      assert(inv.lnTags.expiryTime.isDefined)
      assert(inv.nodeId.hex.startsWith(pubkeyPrefixOpt.get))

      dbOpt match {
        case Some(db) =>
          assert(db.invoice == inv)
          assert(db.state == Unpaid)
          assert(db.hash == CryptoUtil.sha256(db.preimage))
          assert(db.index >= 0)
          assert(db.amountOpt.contains(MilliSatoshis.fromSatoshis(amount)))
          assert(db.amountPaidOpt.isEmpty)

          assert(inv.lnTags.secret.exists(_.secret == db.paymentSecret))
        case None => fail("Could not find in database")
      }
    }
  }

  it must "rotate keys" in { param =>
    val (_, htlc) = param

    val amount = Satoshis(1000)

    for {
      inv <- htlc.createInvoice("hello world", amount, 3600)
      inv2 <- htlc.createInvoice("hello world", amount, 3600)
    } yield assert(inv.nodeId != inv2.nodeId)
  }

  it must "cancel an invoice" in { param =>
    val (_, htlc) = param

    val amount = Satoshis(1000)

    val subF = htlc
      .subscribeInvoices()
      .filter(_.state == Cancelled)
      .runWith(Sink.head)

    for {
      inv <- htlc.createInvoice("hello world", amount, 3600)
      hash = inv.lnTags.paymentHash.hash
      cancelOpt <- htlc.cancelInvoice(hash)
      dbOpt <- htlc.lookupInvoice(hash)
      sub <- subF
    } yield {
      assert(cancelOpt.exists(_.hash == hash))
      assert(cancelOpt.exists(_.state == Cancelled))

      assert(dbOpt.exists(_.hash == hash))
      assert(dbOpt.exists(_.state == Cancelled))

      assert(sub.invoice == inv)
      assert(sub.state == Cancelled)
    }
  }
}
