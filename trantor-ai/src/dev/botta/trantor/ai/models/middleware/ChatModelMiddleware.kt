package dev.botta.trantor.ai.models.middleware

import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.chat.ChatModel
import dev.botta.trantor.ai.models.chat.ChatRequest
import dev.botta.trantor.ai.models.chat.ChatResponse
import dev.botta.trantor.ai.models.chat.ChatStream

/**
 * Something that wraps a [ChatModel] without being one: retry, fallback, cache, defaults, rate limiting, telemetry.
 *
 * Every method has a default, so a middleware implements only what it cares about. [transform] is for changing the
 * request; [generate] and [stream] are for changing the call, and have to end up calling `next` to let it happen.
 */
interface ChatModelMiddleware {
    /** Changes the request before it reaches the model. Runs for every middleware, outermost first. */
    fun transform(request: ChatRequest, model: ChatModel): ChatRequest = request

    fun generate(
        request: ChatRequest,
        options: CallOptions,
        next: (ChatRequest, CallOptions) -> ChatResponse,
    ): ChatResponse = next(request, options)

    fun stream(
        request: ChatRequest,
        options: CallOptions,
        next: (ChatRequest, CallOptions) -> ChatStream,
    ): ChatStream = next(request, options)
}

/**
 * The model seen through [middlewares]. The first one is the outermost: it sees the call before the others and the
 * answer after them.
 */
fun ChatModel.with(middlewares: List<ChatModelMiddleware>): ChatModel =
    if (middlewares.isEmpty()) this else MiddlewareChatModel(this, middlewares)

fun ChatModel.with(vararg middlewares: ChatModelMiddleware) = with(middlewares.toList())

private typealias Call<T> = (ChatRequest, CallOptions) -> T

private class MiddlewareChatModel(
    private val model: ChatModel,
    private val middlewares: List<ChatModelMiddleware>,
): ChatModel {
    override val provider = model.provider
    override val modelId = model.modelId
    override val searchesTools = model.searchesTools

    // Built once and not per call: the registry hands out the same model to everyone who asks for it
    private val generateChain: Call<ChatResponse> =
        middlewares.foldRight({ request, options -> model.generate(request, options) }) { middleware, next ->
            { request, options -> middleware.generate(request, options, next) }
        }

    private val streamChain: Call<ChatStream> =
        middlewares.foldRight({ request, options -> model.stream(request, options) }) { middleware, next ->
            { request, options -> middleware.stream(request, options, next) }
        }

    override fun generate(request: ChatRequest, options: CallOptions) = generateChain(transform(request), options)

    override fun stream(request: ChatRequest, options: CallOptions) = streamChain(transform(request), options)

    private fun transform(request: ChatRequest) =
        middlewares.fold(request) { transformed, middleware -> middleware.transform(transformed, model) }

    override fun toString() = "$model through ${middlewares.joinToString { it::class.simpleName ?: "middleware" }}"
}
