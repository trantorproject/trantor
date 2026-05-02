package dev.botta.trantor.core.jobs.serialization

import dev.botta.trantor.core.jobs.*
import dev.botta.trantor.primitives.serialization.JsonSerializer
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class DefaultJobSerializer(
    private val jsonSerializer: JsonSerializer,
): JobSerializer {
    private val byType = ConcurrentHashMap<String, KClass<out Job>>()

    override fun register(jobClass: KClass<out Job>) {
        val type = jobClass.jobType()
        val existing = byType.putIfAbsent(type, jobClass)
        if (existing != null && existing != jobClass) {
            throw JobTypeCollisionError(
                "Job type '$type' is already registered for ${existing.qualifiedName}, " +
                        "cannot register ${jobClass.qualifiedName}. " +
                        "Use @JobType to disambiguate."
            )
        }
    }

    override fun serialize(job: Job) = SerializedJob(job.jobType, jsonSerializer.serialize(job))

    override fun deserialize(serialized: SerializedJob): Job {
        val jobClass = byType[serialized.type]
            ?: throw JobClassNotFound("No job class registered for type '${serialized.type}'")

        return jsonSerializer.deserialize(serialized.body, jobClass.java)
    }

    override fun isRegistered(jobClass: KClass<out Job>): Boolean = byType[jobClass.jobType()] == jobClass
}

