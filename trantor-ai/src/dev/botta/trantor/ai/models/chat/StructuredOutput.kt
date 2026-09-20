package dev.botta.trantor.ai.models.chat

import dev.botta.trantor.ai.errors.NoObjectGeneratedError
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Reads the answer as the object that was asked for with [OutputSpec.Json].
 *
 * It decodes with kotlinx.serialization, the same descriptor the schema came from.
 */
fun <T> ChatResponse.objectAs(deserializer: DeserializationStrategy<T>): T {
    refusal?.let { throw NoObjectGeneratedError("The model refused to answer", finishReason, it, text) }

    if (text.isBlank()) {
        throw NoObjectGeneratedError("The model answered without text ($finishReason)", finishReason, null, text)
    }

    try {
        return json.decodeFromString(deserializer, text)
    } catch (e: SerializationException) {
        throw NoObjectGeneratedError("The answer is not the object that was asked for", finishReason, null, text, e)
    }
}

inline fun <reified T> ChatResponse.objectAs(): T = objectAs(serializer<T>())

// A model may add fields that the type doesn't have; that is not a reason to lose the answer
private val json = Json { ignoreUnknownKeys = true }
