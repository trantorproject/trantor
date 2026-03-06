package dev.botta.trantor.core.broadcast

import dev.botta.cqbus.identity.Identity
import java.time.LocalDateTime
import java.util.*

interface ClientSession {
    val id: UUID
    val identity: Identity
    val channelSubscriptions: Set<String>
    val createdAt: LocalDateTime

    fun setAttribute(key: String, value: Any?)
    fun <T> getAttribute(key: String): T?
    fun getAttributeKeys(): Set<String>
}
