package dev.botta.trantor.core.jobs.serialization

import dev.botta.trantor.core.jobs.Job
import kotlin.reflect.KClass

interface JobSerializer {
    fun register(jobClass: KClass<out Job>)
    fun serialize(job: Job): SerializedJob
    fun deserialize(serialized: SerializedJob): Job
    fun isRegistered(jobClass: KClass<out Job>): Boolean
}

inline fun <reified T : Job> JobSerializer.register() = register(T::class)

fun JobSerializer.deserialize(type: String, body: String) = deserialize(SerializedJob(type, body))
