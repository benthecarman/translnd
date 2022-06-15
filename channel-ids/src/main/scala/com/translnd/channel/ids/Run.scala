package com.translnd.channel.ids

object Run extends App {

  println(s"Running!")
  val path = ExistingChannelId.createMinifiedBinaryFile()
  println(s"Created file at $path")
}
