package dev.botta.trantor.domain

import java.util.*

class Id<T>(private val rawId: String = UUID.randomUUID().toString()) {
    override fun equals(other: Any?) = other is Id<*> && other.javaClass == this.javaClass && other.rawId == rawId

    override fun hashCode() = rawId.hashCode()

    override fun toString() = rawId
}
