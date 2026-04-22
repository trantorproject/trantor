package dev.botta.trantor.core.queues

data class Message(val type: String, val body: String, val cid: String? = null)
