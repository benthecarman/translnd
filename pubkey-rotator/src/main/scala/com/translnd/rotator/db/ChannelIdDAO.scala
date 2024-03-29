package com.translnd.rotator.db

import com.translnd.rotator.config.TransLndAppConfig
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
  import mappers._

  override val table: TableQuery[ChannelIdTable] = TableQuery[ChannelIdTable]

  def findByScidAction(scid: ShortChannelId): DBIOAction[Vector[ChannelIdDb],
                                                         NoStream,
                                                         Effect.Read] = {
    table.filter(_.scid === scid).result.map(_.toVector)
  }

  class ChannelIdTable(tag: Tag)
      extends TableAutoInc[ChannelIdDb](tag, schemaName, "channel_ids") {

    def hash: Rep[Sha256Digest] = column("hash")
    def scid: Rep[ShortChannelId] = column("scid")

    def * : ProvenShape[ChannelIdDb] =
      (id.?, hash, scid).<>(ChannelIdDb.tupled, ChannelIdDb.unapply)
  }
}
