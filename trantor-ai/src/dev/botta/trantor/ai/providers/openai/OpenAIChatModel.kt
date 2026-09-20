package dev.botta.trantor.ai.providers.openai

import dev.botta.json.Json
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.web.client.*
import dev.botta.trantor.web.client.okhttp.OkHttpHttpClient
import dev.botta.trantor.web.client.okhttp.OkHttpHttpClientConfig
import kotlin.time.TimeSource

/**
 * Chat model on top of the OpenAI Responses API.
 *
 * Calls go through [HttpClient.stream] even when the whole answer is read at once: it is the entry point that takes
 * the timeout of the call and gives a handle to cancel it while it runs.
 */
class OpenAIChatModel(
    override val modelId: String,
    private val config: OpenAIConfig = OpenAIConfig(),
    private val httpClient: HttpClient = defaultHttpClient,
): ChatModel {
    constructor(modelId: String, apiKey: String): this(modelId, OpenAIConfig(apiKey))

    override val provider = OPENAI_PROVIDER

    private val requestMapper = OpenAIRequestMapper(config)
    private val errorMapper = OpenAIErrorMapper()
    private val responseMapper = OpenAIResponseMapper()

    override fun generate(request: ChatRequest, options: CallOptions): ChatResponse {
        options.cancellation?.throwIfCancelled()

        val mapped = requestMapper.map(modelId, request)
        val startedAt = TimeSource.Monotonic.markNow()

        try {
            call(mapped.body.toString(), options).use { response ->
                val body = response.body()

                if (response.status != 200) throw errorMapper.toError(response, body)

                val json = Json.parse(body).asObject() ?: throw errorMapper.toError(response, body)

                return responseMapper.map(json, modelId, startedAt.elapsedNow(), mapped.warnings)
            }
        } catch (e: Throwable) {
            throw errorMapper.toError(e)
        }
    }

    override fun stream(request: ChatRequest, options: CallOptions): ChatStream {
        options.cancellation?.throwIfCancelled()

        val mapped = requestMapper.map(modelId, request, stream = true)
        val startedAt = TimeSource.Monotonic.markNow()

        val response = try {
            call(mapped.body.toString(), options)
        } catch (e: Throwable) {
            throw errorMapper.toError(e)
        }

        if (response.status != 200) {
            val body = response.use { it.body() }
            throw errorMapper.toError(response, body)
        }

        return OpenAIChatStream(modelId, response, startedAt, mapped.warnings, responseMapper)
    }

    private fun call(body: String, options: CallOptions): HttpStreamResponse {
        val httpRequest = HttpRequest("${config.baseUrl}/responses", body, headers(options))
        val streamOptions = StreamOptions(totalTimeout = options.timeout?.inWholeMilliseconds?.toInt())
        val response = httpClient.stream(HttpMethods.Post, httpRequest, streamOptions)

        options.cancellation?.onCancel { response.cancel() }

        return response
    }

    private fun headers(options: CallOptions) = buildMap {
        put("Content-Type", "application/json")
        put("Authorization", "Bearer ${config.apiKey}")
        config.organization?.let { put("OpenAI-Organization", it) }
        config.project?.let { put("OpenAI-Project", it) }
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
