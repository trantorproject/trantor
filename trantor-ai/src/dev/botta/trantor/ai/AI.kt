package dev.botta.trantor.ai

import dev.botta.trantor.ai.generation.GenerateRequest
import dev.botta.trantor.ai.generation.ObjectResult
import dev.botta.trantor.ai.generation.RunResult
import dev.botta.trantor.ai.generation.RunStream
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ModelRegistry
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * The way into models for a use case: a line for the simple call, and a run with tools when it needs one.
 *
 * ```kotlin
 * val summary = ai.text("Summarize in 3 bullets:\n$text", model = "fast")
 *
 * val result = ai.generate {
 *     user(question)
 *     tools(searchProducts)
 * }
 *
 * val invoice = ai.generate<InvoiceData> { user("Extract the invoice data: $text") }
 *
 * ai.stream { user(question) }.use { stream -> stream.textDeltas().forEach { print(it) } }
 * ```
 *
 * It keeps no state, so it is injected like any other service and mocked in the tests of a use case. Whoever needs
 * a model itself asks [models] for it.
 */
interface AI {
    /** The text the model answers to [prompt]. [model] is a reference or an alias; null is `default`. */
    fun text(prompt: String, model: String? = null, options: CallOptions = CallOptions()): String

    /** A run: the model, and the tools it asks for, until it answers. */
    fun generate(request: GenerateRequest): RunResult

    /** A run whose answer is an object of the type [serializer] describes. It does not fail when it does not come. */
    fun <T> generateObject(request: GenerateRequest, serializer: KSerializer<T>): ObjectResult<T>

    /** The same run, received as it happens. Use it with `use {}`: closing it cancels the call in flight. */
    fun stream(request: GenerateRequest): RunStream

    fun models(): ModelRegistry
}

fun AI.generate(build: GenerateRequest.() -> Unit) = generate(GenerateRequest().apply(build))

fun AI.stream(build: GenerateRequest.() -> Unit) = stream(GenerateRequest().apply(build))

/**
 * The answer as an object of type [T], a @Serializable type whose schema is sent to the model.
 *
 * @throws dev.botta.trantor.ai.errors.NoObjectGeneratedError when the model refused, ran out of tokens or wrote
 * something that is not a [T]. [generateObject] says so without failing.
 */
inline fun <reified T> AI.generate(noinline build: GenerateRequest.() -> Unit): T =
    generateObject<T>(build).getOrThrow()

inline fun <reified T> AI.generateObject(noinline build: GenerateRequest.() -> Unit) =
    generateObject(GenerateRequest().apply(build), serializer<T>())
