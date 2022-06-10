package com.translnd.htlc.config

import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.protocol.ln.channel.ShortChannelId
import org.bitcoins.commons.serializers.JsonReaders._
import play.api.libs.json._

import scala.util._

import scala.io.Source

case class ExistingChannelId(
    block_height: BigInt,
    block_index: Int,
    transaction_index: Int,
    amount: Satoshis) {

  lazy val scid: ShortChannelId =
    ShortChannelId(block_height, block_index, transaction_index)
}

object ExistingChannelIds {
  implicit val reads: Reads[ExistingChannelId] = Json.reads[ExistingChannelId]

  private lazy val resource: JsArray = {
    val resource = Source.fromResource("channelIds.json")
    val json = Json.parse(resource.getLines().mkString)
    json.validate[JsArray].get
  }

  lazy val all: Vector[ExistingChannelId] = resource.value.toVector.flatMap {
    value =>
      value.validate[ExistingChannelId].asOpt
  }

  def getChannelId(amount: Satoshis): ShortChannelId = {
    val shuffled = Random.shuffle(all)
    val bestOpt = shuffled.find(_.amount > amount * Satoshis(2)).map(_.scid)
    val secondBestOpt = shuffled.find(_.amount > amount).map(_.scid)

    bestOpt.orElse(secondBestOpt).getOrElse(shuffled.head.scid)
  }
}
