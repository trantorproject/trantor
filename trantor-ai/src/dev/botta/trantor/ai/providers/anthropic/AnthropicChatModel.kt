package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.Json
import dev.botta.trantor.ai.errors.AuthenticationError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.CancellationLink
import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.web.client.*
import dev.botta.trantor.web.client.okhttp.OkHttpHttpClient
import dev.botta.trantor.web.client.okhttp.OkHttpHttpClientConfig
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

    private val requestMapper = AnthropicRequestMapper(config, catalog)
    private val errorMapper = AnthropicErrorMapper()

    override fun generate(request: ChatRequest, options: CallOptions): ChatResponse {
        options.cancellation?.throwIfCancelled()

        val mapped = requestMapper.map(modelId, request)
        val responseMapper = AnthropicResponseMapper(mapped.stamp)
        val startedAt = TimeSource.Monotonic.markNow()

        try {
            CancellationLink(options.cancellation).use { link ->
                call(mapped, options, link).use { response ->
                    val body = response.body()

                    // Cancelling closes the connection, so what came back is half a body and not an answer
                    link.throwIfCancelled()

                    if (response.status != 200) throw errorMapper.toError(response, body)

                    val json = Json.parse(body).asObject() ?: throw errorMapper.toError(response, body)

                    return responseMapper.map(json, modelId, startedAt.elapsedNow(), mapped.warnings)
                }
            }
        } catch (e: Throwable) {
            throw errorMapper.toError(e)
        }
    }

    override fun stream(request: ChatRequest, options: CallOptions): ChatStream {
        options.cancellation?.throwIfCancelled()

        val mapped = requestMapper.map(modelId, request, stream = true)
        val startedAt = TimeSource.Monotonic.markNow()
        // The link lives as long as the stream, and the stream closes it
        val link = CancellationLink(options.cancellation)

        val response = try {
            call(mapped, options, link)
        } catch (e: Throwable) {
            link.close()
            throw errorMapper.toError(e)
        }

        if (response.status != 200) {
            val body = response.use { it.body() }
            link.close()
            throw errorMapper.toError(response, body)
        }

        val responseMapper = AnthropicResponseMapper(mapped.stamp)

        return AnthropicChatStream(modelId, response, startedAt, mapped.warnings, responseMapper, link)
    }

    private fun call(mapped: MappedRequest, options: CallOptions, link: CancellationLink): HttpStreamResponse {
        val httpRequest = HttpRequest("${config.baseUrl}/messages", mapped.body.toString(), headers(mapped, options))
        val streamOptions = StreamOptions(totalTimeout = options.timeout?.inWholeMilliseconds?.toInt())

        return link.attach(httpClient.stream(HttpMethods.Post, httpRequest, streamOptions))
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

    companion object {
        /**
         * Shared by every model built without one, so that several models don't end up with a connection pool each.
         * Its timeouts are the ones a generation needs: a model can take a while to answer, and a long answer is not
         * a reason to cut the call as long as it keeps coming.
         */
        private val defaultHttpClient: HttpClient by lazy {
            OkHttpHttpClient(OkHttpHttpClientConfig(idleTimeout = 120_000, requestTimeout = 0))
        }
    }
}
