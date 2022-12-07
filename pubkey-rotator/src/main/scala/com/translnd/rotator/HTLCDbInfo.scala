package com.translnd.rotator

import com.translnd.rotator.db._

sealed trait HTLCDbInfo

case class Payment(scids: Vector[ChannelIdDb], invoiceDb: InvoiceDb)
    extends HTLCDbInfo

case class Probe(scids: Vector[ChannelIdDb], invoiceDbs: Vector[InvoiceDb])
    extends HTLCDbInfo
