package dev.botta.trantor.core.jobs

import com.github.f4b6a3.uuid.UuidCreator
import dev.botta.time.Clock
import dev.botta.trantor.primitives.lang.describe
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

private val jobTypeCache = ConcurrentHashMap<KClass<*>, String>()

abstract class Job(open val id: UUID = UuidCreator.getTimeOrderedEpoch()) {
    val jobType: String get() = this::class.jobType()
    val createdAt = Clock.now()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Job) return false
        if (this::class != other::class) return false
        return id == other.id
    }

    override fun hashCode() = id.hashCode()

    override fun toString() = describe("id=$id", "createdAt=$createdAt")
}

fun KClass<*>.jobType(): String =
    jobTypeCache.getOrPut(this) {
        findAnnotation<JobType>()?.value
            ?: simpleName
            ?: error("Cannot derive job type from anonymous class. Use @JobType.")
    }
