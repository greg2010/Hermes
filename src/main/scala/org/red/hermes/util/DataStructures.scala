package org.red.hermes.util

sealed trait Credentials

case class TeamspeakGroupMapEntry(bit_name: String, teamspeak_group_id: Int)
