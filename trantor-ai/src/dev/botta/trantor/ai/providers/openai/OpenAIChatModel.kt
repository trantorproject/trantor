package dev.botta.trantor.ai.providers.openai

import dev.botta.json.Json
import dev.botta.trantor.ai.errors.AuthenticationError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.CancellationLink
import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.ai.models.chat.*
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

    private val requestMapper = OpenAIRequestMapper(config, catalog)
    private val errorMapper = OpenAIErrorMapper()
    private val responseMapper = OpenAIResponseMapper()

    override fun generate(request: ChatRequest, options: CallOptions): ChatResponse {
        options.cancellation?.throwIfCancelled()

        val mapped = requestMapper.map(modelId, request)
        val startedAt = TimeSource.Monotonic.markNow()

        try {
            CancellationLink(options.cancellation).use { link ->
                call(mapped.body.toString(), options, link).use { response ->
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
            call(mapped.body.toString(), options, link)
        } catch (e: Throwable) {
            link.close()
            throw errorMapper.toError(e)
        }

        if (response.status != 200) {
            val body = response.use { it.body() }
            link.close()
            throw errorMapper.toError(response, body)
        }

        return OpenAIChatStream(modelId, response, startedAt, mapped.warnings, responseMapper, link)
    }

    private fun call(body: String, options: CallOptions, link: CancellationLink): HttpStreamResponse {
        val httpRequest = HttpRequest("${config.baseUrl}/responses", body, headers(options))
        val streamOptions = StreamOptions(
            readTimeout = config.readTimeout,
            totalTimeout = options.timeout?.inWholeMilliseconds?.toInt(),
        )

        return link.attach(httpClient.stream(HttpMethods.Post, httpRequest, streamOptions))
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
