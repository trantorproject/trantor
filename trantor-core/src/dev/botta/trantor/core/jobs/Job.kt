package dev.botta.trantor.core.jobs

import dev.botta.time.Clock
import dev.botta.trantor.primitives.lang.describe
import java.util.*

abstract class Job(open val id: UUID = UUID.randomUUID()) {
    val createdAt = Clock.now()

    override fun equals(other: Any?) = other is Job && other.id == id

    override fun hashCode() = id.hashCode()

    override fun toString() = describe("id=$id", "createdAt=$createdAt")
}
