package com.translnd.htlc.db

import com.translnd.htlc.InvoiceState
import com.translnd.htlc.InvoiceState._
import com.translnd.htlc.config.TransLndAppConfig
import org.bitcoins.core.protocol.ln._
import org.bitcoins.core.protocol.ln.currency.MilliSatoshis
import org.bitcoins.core.util.TimeUtil
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
  import mappers._

  implicit val InvoiceStateMapper: BaseColumnType[InvoiceState] =
    MappedColumnType.base[InvoiceState, String](_.toString,
                                                InvoiceState.fromString)

  override val table: TableQuery[InvoiceTable] = TableQuery[InvoiceTable]

  override def createAll(ts: Vector[InvoiceDb]): Future[Vector[InvoiceDb]] =
    createAllNoAutoInc(ts, safeDatabase)

  override protected def findByPrimaryKeys(
      ids: Vector[Sha256Digest]): Query[InvoiceTable, InvoiceDb, Seq] =
    table.filter(_.hash.inSet(ids))

  override protected def findAll(
      ts: Vector[InvoiceDb]): Query[InvoiceTable, InvoiceDb, Seq] =
    findByPrimaryKeys(ts.map(_.hash))

  def markInvoicesExpired(): Future[Vector[InvoiceDb]] = {
    val now = TimeUtil.currentEpochSecond
    val findAction = table
      .filter(_.expireTime < now)
      .filter(_.state === Unpaid.asInstanceOf[InvoiceState])
      .result
      .map(_.toVector)

    val action = findAction.flatMap { dbs =>
      val updated = dbs.map(_.copy(state = Expired))
      updateAllAction(updated)
    }
    safeDatabase.runVec(action)
  }

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

    def expireTime: Rep[Option[Long]] = column("expire_time")

    def invoice: Rep[LnInvoice] = column("invoice", O.Unique)

    def idx: Rep[Int] = column("idx", O.Unique)

    def state: Rep[InvoiceState] = column("state")

    def * : ProvenShape[InvoiceDb] =
      (hash, preimage, paymentSecret, amount, expireTime, invoice, idx, state)
        .<>(InvoiceDb.tupled, InvoiceDb.unapply)
  }
}
