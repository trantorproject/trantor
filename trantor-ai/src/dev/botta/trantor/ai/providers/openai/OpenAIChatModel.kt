package dev.botta.trantor.ai.providers.openai

import dev.botta.json.Json
import dev.botta.trantor.ai.errors.AuthenticationError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.ai.models.catalog.ModelFeatures.ToolSearch
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.throwIfCancelled
import dev.botta.trantor.web.client.*
import dev.botta.trantor.ai.providers.defaultHttpClient
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
    catalog: ModelCatalog = ModelCatalog().addOpenAIModels(),
): ChatModel {
    constructor(modelId: String, apiKey: String): this(modelId, OpenAIConfig(apiKey))

    override val provider = OPENAI_PROVIDER

    /** Not taken for granted of a model the catalog does not know: one that does not search answers 400. */
    override val searchesTools = catalog.find(OPENAI_PROVIDER, modelId)?.capabilities?.contains(ToolSearch) == true

    private val requestMapper = OpenAIRequestMapper(config, catalog)
    private val errorMapper = OpenAIErrorMapper()
    private val responseMapper = OpenAIResponseMapper()

    override fun generate(request: ChatRequest, options: CallOptions): ChatResponse {
        options.cancellation?.throwIfCancelled()

        val mapped = requestMapper.map(modelId, request)
        val startedAt = TimeSource.Monotonic.markNow()

        try {
            call(mapped.body.toString(), options).use { response ->
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
            call(mapped.body.toString(), options)
        } catch (e: Throwable) {
            options.cancellation?.throwIfCancelled()
            throw errorMapper.toError(e)
        }

        if (response.status != 200) {
            val body = response.use { it.body() }
            options.cancellation?.throwIfCancelled()
            throw errorMapper.toError(response, body)
        }

        return OpenAIChatStream(modelId, response, startedAt, mapped.warnings, responseMapper, options.cancellation)
    }

    private fun call(body: String, options: CallOptions): HttpStreamResponse {
        val httpRequest = HttpRequest("${config.baseUrl}/responses", body, headers(options))
        val streamOptions = StreamOptions(
            readTimeout = config.readTimeout,
            totalTimeout = options.timeout?.inWholeMilliseconds?.toInt(),
            cancellation = options.cancellation,
        )

        return httpClient.stream(HttpMethods.Post, httpRequest, streamOptions)
    }

    private fun headers(options: CallOptions) = buildMap {
        if (config.apiKey.isBlank()) {
            throw AuthenticationError(
                provider,
                "There is no OpenAI api key. Set the OPENAI_API_KEY environment variable, " +
                    "or ai.providers.openai.apiKey in the configuration.",
            )
        }

        put("Content-Type", "application/json")
        put("Authorization", "Bearer ${config.apiKey}")
        config.organization?.let { put("OpenAI-Organization", it) }
        config.project?.let { put("OpenAI-Project", it) }
        putAll(options.headers)
    }
}
