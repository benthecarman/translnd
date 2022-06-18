package com.translnd.rotator

import org.bitcoins.crypto.StringFactory

sealed abstract class InvoiceState

object InvoiceState extends StringFactory[InvoiceState] {
  case object Unpaid extends InvoiceState
  case object Paid extends InvoiceState
  case object Expired extends InvoiceState
  case object Cancelled extends InvoiceState
  case object Accepted extends InvoiceState

  val all: Vector[InvoiceState] =
    Vector(Unpaid, Paid, Expired, Cancelled, Accepted)

  override def fromStringOpt(string: String): Option[InvoiceState] = {
    val searchString = string.toLowerCase
    all.find(_.toString.toLowerCase == searchString)
  }

  override def fromString(string: String): InvoiceState = {
    fromStringOpt(string).getOrElse(
      throw new RuntimeException(
        s"Could not find an InvoiceState for string $string"))
  }
}
