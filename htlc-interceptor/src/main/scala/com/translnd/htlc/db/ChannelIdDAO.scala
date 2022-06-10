package com.translnd.htlc.db

import com.translnd.htlc.config.TransLndAppConfig
import org.bitcoins.core.protocol.ln.channel.ShortChannelId
import org.bitcoins.crypto._
import org.bitcoins.db._
import slick.lifted.ProvenShape

import scala.concurrent._

case class ChannelIdDAO()(implicit
    override val ec: ExecutionContext,
    override val appConfig: TransLndAppConfig)
    extends CRUDAutoInc[ChannelIdDb] {

  import profile.api._

  private val mappers = new DbCommonsColumnMappers(profile)
  private val mappers2 = new DbMappers(profile)
  import mappers._
  import mappers2._

  override val table: TableQuery[ChannelIdTable] = TableQuery[ChannelIdTable]

  def findByHash(hash: Sha256Digest): Future[Vector[ChannelIdDb]] = {
    val query = table.filter(_.hash === hash).result

    safeDatabase.runVec(query)
  }

  class ChannelIdTable(tag: Tag)
      extends TableAutoInc[ChannelIdDb](tag, schemaName, "channel_ids") {

    def hash: Rep[Sha256Digest] = column("hash")
    def scid: Rep[ShortChannelId] = column("scid")

    def * : ProvenShape[ChannelIdDb] =
      (id.?, hash, scid).<>(ChannelIdDb.tupled, ChannelIdDb.unapply)
  }
}
