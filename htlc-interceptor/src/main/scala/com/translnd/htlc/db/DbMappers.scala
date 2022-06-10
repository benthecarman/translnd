package com.translnd.htlc.db

import org.bitcoins.core.number.UInt64
import org.bitcoins.core.protocol.ln._
import org.bitcoins.core.protocol.ln.channel.ShortChannelId
import org.bitcoins.core.protocol.ln.currency._
import slick.jdbc.JdbcProfile

import java.util.UUID

class DbMappers(val profile: JdbcProfile) {

  import profile.api._

  implicit val PaymentSecretMapper: BaseColumnType[PaymentSecret] =
    MappedColumnType.base[PaymentSecret, String](_.hex, PaymentSecret.fromHex)

  implicit val ShortChannelIdMapper: BaseColumnType[ShortChannelId] =
    MappedColumnType.base[ShortChannelId, Long](_.u64.toLong,
                                                l => ShortChannelId(UInt64(l)))

  implicit val MilliSatoshisMapper: BaseColumnType[MilliSatoshis] =
    MappedColumnType.base[MilliSatoshis, Long](_.toLong, MilliSatoshis.apply(_))

  implicit val UUIDMapper: BaseColumnType[UUID] =
    MappedColumnType.base[UUID, String](_.toString, UUID.fromString)
}
