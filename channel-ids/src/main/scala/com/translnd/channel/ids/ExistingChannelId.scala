package com.translnd.channel.ids

import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.protocol.ln.channel.ShortChannelId
import org.bitcoins.commons.serializers.JsonReaders._
import org.bitcoins.core.protocol.BigSizeUInt
import org.bitcoins.crypto.{Factory, NetworkElement}
import play.api.libs.json._
import scodec.bits.ByteVector

import java.nio.file.{Files, Path, Paths}
import scala.annotation.tailrec
import scala.util.{Properties, Random}
import scala.io.Source
import scala.reflect.io.Directory

case class ExistingChannelId(
    block_height: BigInt,
    block_index: Int,
    transaction_index: Int,
    amount: Satoshis)
    extends NetworkElement {

  lazy val scid: ShortChannelId =
    ShortChannelId(block_height, block_index, transaction_index)

  override def bytes: ByteVector = {
    val blockHeight = BigSizeUInt(block_height).bytes
    val blockIdx = BigSizeUInt(block_index).bytes
    val txIdx = BigSizeUInt(transaction_index).bytes

    blockHeight ++ blockIdx ++ txIdx ++ BigSizeUInt(amount.toUInt64).bytes
  }
}

/** Used for creating the minified version of all the private channel ids */
object ExistingChannelId extends Factory[ExistingChannelId] {

  override def fromBytes(bytes: ByteVector): ExistingChannelId = {
    val block_height = BigSizeUInt.fromBytes(bytes)
    val drop1 = bytes.drop(block_height.byteSize)

    val block_index = BigSizeUInt.fromBytes(drop1)
    val drop2 = drop1.drop(block_index.byteSize)

    val transaction_index = BigSizeUInt.fromBytes(drop2)
    val drop3 = drop2.drop(transaction_index.byteSize)

    val amount = BigSizeUInt.fromBytes(drop3)

    ExistingChannelId(block_height.toBigInt,
                      block_index.toInt,
                      transaction_index.toInt,
                      Satoshis(amount.toBigInt))
  }

  val FILE_SIZE: Int = 11122362 // exact size of the file

  lazy val all: Vector[ExistingChannelId] = {
    val byteArr = new Array[Byte](FILE_SIZE)
    val stream = getClass.getResource("/channelIds").openStream()
    val bytesRead = stream.read(byteArr)
    stream.close()

    require(FILE_SIZE == bytesRead, "Update FILE_SIZE value")
    val bytes = ByteVector.view(byteArr)

    @tailrec
    def loop(
        accum: Vector[ExistingChannelId],
        remaining: ByteVector): Vector[ExistingChannelId] = {
      if (remaining.isEmpty) {
        accum
      } else {
        val item = fromBytes(remaining)
        loop(accum :+ item, remaining.drop(item.byteSize))
      }
    }

    loop(Vector.empty, bytes)
  }

  def getChannelId(amount: Satoshis): ShortChannelId = {
    val shuffled = Random.shuffle(all)
    val bestOpt = shuffled.find(_.amount > amount * Satoshis(2)).map(_.scid)
    val secondBestOpt = shuffled.find(_.amount > amount).map(_.scid)

    bestOpt.orElse(secondBestOpt).getOrElse(shuffled.head.scid)
  }

  private lazy val jsonResource: Vector[ExistingChannelId] = {
    implicit val reads: Reads[ExistingChannelId] = Json.reads[ExistingChannelId]
    val path = Paths.get(Properties.userHome, "Documents", "hidden-ln")
    val dir = Directory(path.toFile)
    val vec = dir.files.map { file =>
      val resource = Source.fromInputStream(file.inputStream())
      val json = Json.parse(resource.getLines().mkString)
      json.validate[JsArray].get
    }
    val js = vec.foldLeft(JsArray.empty)(_ ++ _)

    js.value.toVector.map { value =>
      value.validate[ExistingChannelId].get
    }
  }

  def createMinifiedJsonFile(): Path = {
    val js = jsonResource.map { chan =>
      JsObject(
        Vector("scid" -> JsNumber(chan.scid.u64.toLong),
               "amt" -> JsNumber(chan.amount.toLong)))
    }

    val str = JsArray(js).toString()
    val path =
      Paths.get(Properties.userHome, "Documents", "channelIds.json")
    Files.write(path, str.getBytes)
  }

  def createMinifiedBinaryFile(): Path = {
    val bytes = jsonResource.foldLeft(ByteVector.empty)(_ ++ _.bytes)

    val path = Paths.get(Properties.userHome, "Documents", "channelIds")
    Files.write(path, bytes.toArray)
  }
}
