package dev.botta.trantor.domain

import com.github.f4b6a3.uuid.UuidCreator
import java.util.*

open class Id(private val rawId: UUID = UuidCreator.getTimeOrderedEpoch()) {
    constructor(raw: String): this(UUID.fromString(raw))

    override fun equals(other: Any?) = other is Id && other.javaClass == this.javaClass && other.rawId == rawId

    override fun hashCode() = rawId.hashCode()

    override fun toString() = rawId.toString()

    fun toUUID() = rawId
}
