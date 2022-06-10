package com.translnd.htlc.db

import com.translnd.htlc.config.TransLndAppConfig
import org.bitcoins.core.protocol.ln._
import org.bitcoins.core.protocol.ln.channel.ShortChannelId
import org.bitcoins.core.protocol.ln.currency.MilliSatoshis
import org.bitcoins.crypto._
import org.bitcoins.db._
import scodec.bits.ByteVector
import slick.lifted.ProvenShape

import scala.concurrent._

case class InvoiceDAO()(implicit
    override val ec: ExecutionContext,
    override val appConfig: TransLndAppConfig)
    extends CRUD[InvoiceDb, Sha256Digest]
    with SlickUtil[InvoiceDb, Sha256Digest] {

  import profile.api._

  private val mappers = new DbCommonsColumnMappers(profile)
  private val mappers2 = new DbMappers(profile)
  import mappers._
  import mappers2._

  override val table: TableQuery[InvoiceTable] = TableQuery[InvoiceTable]

  override def createAll(ts: Vector[InvoiceDb]): Future[Vector[InvoiceDb]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(
      ids: Vector[Sha256Digest]): Query[InvoiceTable, InvoiceDb, Seq] =
    table.filter(_.hash.inSet(ids))

  override protected def findAll(
      ts: Vector[InvoiceDb]): Query[InvoiceTable, InvoiceDb, Seq] =
    findByPrimaryKeys(ts.map(_.hash))

  def maxIndex(): Future[Option[Int]] = {
    val query = table.map(_.idx).max

    safeDatabase.run(query.result)
  }

  class InvoiceTable(tag: Tag)
      extends Table[InvoiceDb](tag, schemaName, "invoices") {

    def hash: Rep[Sha256Digest] = column("hash", O.PrimaryKey)

    def preimage: Rep[ByteVector] = column("preimage", O.Unique)

    def paymentSecret: Rep[PaymentSecret] = column("payment_secret", O.Unique)

    def amount: Rep[Option[MilliSatoshis]] = column("amount")

    def invoice: Rep[LnInvoice] = column("invoice", O.Unique)

    def idx: Rep[Int] = column("idx", O.Unique)

    def chanId: Rep[ShortChannelId] = column("chan_id")

    def settled: Rep[Boolean] = column("settled")

    def * : ProvenShape[InvoiceDb] =
      (hash, preimage, paymentSecret, amount, invoice, chanId, idx, settled).<>(
        InvoiceDb.tupled,
        InvoiceDb.unapply)
  }
}
