package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.Json
import dev.botta.trantor.ai.errors.AuthenticationError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.providers.defaultHttpClient
import dev.botta.trantor.ai.throwIfCancelled
import dev.botta.trantor.web.client.*
import kotlin.time.TimeSource

/**
 * Chat model on top of the Anthropic Messages API.
 *
 * Calls go through [HttpClient.stream] even when the whole answer is read at once: it is the entry point that takes
 * the timeout of the call and gives a handle to cancel it while it runs.
 */
class AnthropicChatModel(
    override val modelId: String,
    private val config: AnthropicConfig = AnthropicConfig(),
    private val httpClient: HttpClient = defaultHttpClient,
    catalog: ModelCatalog = ModelCatalog().addAnthropicModels(),
): ChatModel {
    constructor(modelId: String, apiKey: String): this(modelId, AnthropicConfig(apiKey))

    override val provider = ANTHROPIC_PROVIDER
    override val loadsDeferredTools = ModelSupport(catalog.find(ANTHROPIC_PROVIDER, modelId)).deferredTools

    private val requestMapper = AnthropicRequestMapper(config, catalog)
    private val errorMapper = AnthropicErrorMapper()

    override fun generate(request: ChatRequest, options: CallOptions): ChatResponse {
        options.cancellation?.throwIfCancelled()

        val mapped = requestMapper.map(modelId, request)
        val responseMapper = AnthropicResponseMapper(mapped.stamp, mapped.notes)
        val startedAt = TimeSource.Monotonic.markNow()

        try {
            call(mapped, options).use { response ->
                val body = response.body()

                // Cancelling closes the connection, so what came back is half a body and not an answer
                options.cancellation?.throwIfCancelled()

                if (response.status != 200) throw errorMapper.toError(response, body)

                val json = Json.parse(body).asObject() ?: throw errorMapper.toError(response, body)

                return responseMapper.map(json, modelId, startedAt.elapsedNow(), mapped.warnings)
            }
        } catch (e: Throwable) {
            // A call cut while it waits for the answer fails on its way, which was not the fault of the provider
            options.cancellation?.throwIfCancelled()
            throw errorMapper.toError(e)
        }
    }

    override fun stream(request: ChatRequest, options: CallOptions): ChatStream {
        options.cancellation?.throwIfCancelled()

        val mapped = requestMapper.map(modelId, request, stream = true)
        val startedAt = TimeSource.Monotonic.markNow()

        val response = try {
            call(mapped, options)
        } catch (e: Throwable) {
            options.cancellation?.throwIfCancelled()
            throw errorMapper.toError(e)
        }

        if (response.status != 200) {
            val body = response.use { it.body() }
            options.cancellation?.throwIfCancelled()
            throw errorMapper.toError(response, body)
        }

        val responseMapper = AnthropicResponseMapper(mapped.stamp, mapped.notes)

        return AnthropicChatStream(modelId, response, startedAt, mapped.warnings, responseMapper, options.cancellation)
    }

    private fun call(mapped: MappedRequest, options: CallOptions): HttpStreamResponse {
        val httpRequest = HttpRequest("${config.baseUrl}/messages", mapped.body.toString(), headers(mapped, options))
        val streamOptions = StreamOptions(
            readTimeout = config.readTimeout,
            totalTimeout = options.timeout?.inWholeMilliseconds?.toInt(),
            cancellation = options.cancellation,
        )

        return httpClient.stream(HttpMethods.Post, httpRequest, streamOptions)
    }

    private fun headers(mapped: MappedRequest, options: CallOptions) = buildMap {
        if (config.apiKey.isBlank()) {
            throw AuthenticationError(
                provider,
                "There is no Anthropic api key. Set the ANTHROPIC_API_KEY environment variable, " +
                    "or ai.providers.anthropic.apiKey in the configuration.",
            )
        }

        put("Content-Type", "application/json")
        // A header and not a bearer token: Anthropic takes the key as x-api-key
        put("x-api-key", config.apiKey)
        put("anthropic-version", config.version)
        // The ones the application asked for, and the ones this request needs for what the adapter wrote in it
        (config.betas + mapped.betas).distinct().takeIf { it.isNotEmpty() }
            ?.let { put("anthropic-beta", it.joinToString(",")) }
        putAll(options.headers)
    }
}
