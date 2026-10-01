package dev.botta.trantor.ai.models.chat

import com.google.gson.JsonParseException
import dev.botta.trantor.ai.generation.NoObjectGeneratedError
import dev.botta.trantor.ai.serialization.read
import dev.botta.trantor.primitives.serialization.JsonSerializer
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Reads the answer as the object of [type] that was asked for with [OutputSpec.Json], with [serializer]: the one of
 * the application, which told the model the schema and reads by the same rules. A field the type does not have is
 * left out, since a model may add one and that is no reason to lose the answer.
 *
 * @throws NoObjectGeneratedError when the model refused, answered nothing or wrote something that is not a [type].
 */
fun <T> ChatResponse.objectAs(type: KType, serializer: JsonSerializer): T {
    refusal?.let { throw NoObjectGeneratedError("The model refused to answer", finishReason, it, text) }

    if (text.isBlank()) {
        throw NoObjectGeneratedError("The model answered without text ($finishReason)", finishReason, null, text)
    }

    try {
        return serializer.read(text, type)
    } catch (e: JsonParseException) {
        throw notTheObject(e)
    } catch (e: IllegalArgumentException) {
        // What a require in an init block of the type throws, which is how a type validates itself
        throw notTheObject(e)
    }
}

/** The answer read as a [T] by [serializer], for a call that asked for JSON. */
inline fun <reified T> ChatResponse.objectAs(serializer: JsonSerializer): T = objectAs(typeOf<T>(), serializer)

private fun ChatResponse.notTheObject(cause: Exception) =
    NoObjectGeneratedError("The answer is not the object that was asked for", finishReason, null, text, cause)
