package com.translnd.channel.ids

import org.bitcoins.testkitcore.util.BitcoinSUnitTest

import scala.util._

class ExistingChannelIdTest extends BitcoinSUnitTest {

  it must "be able to parse the channel ids" in {
    Try(ExistingChannelId.all) match {
      case Failure(exception) => fail(exception)
      case Success(value)     => assert(value.nonEmpty)
    }
  }
}
