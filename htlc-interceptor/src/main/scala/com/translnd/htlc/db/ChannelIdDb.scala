package com.translnd.htlc.db

import org.bitcoins.core.api.db.DbRowAutoInc
import org.bitcoins.core.protocol.ln.channel.ShortChannelId
import org.bitcoins.crypto.Sha256Digest

case class ChannelIdDb(
    id: Option[Long],
    hash: Sha256Digest,
    scid: ShortChannelId
) extends DbRowAutoInc[ChannelIdDb] {
  override def copyWithId(id: Long): ChannelIdDb = copy(id = Some(id))
}

object ChannelIdDbs {

  def apply(
      hash: Sha256Digest,
      scids: Vector[ShortChannelId]): Vector[ChannelIdDb] = {
    scids.map(ChannelIdDb(None, hash, _))
  }
}
