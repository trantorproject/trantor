package dev.botta.trantor.ai

import dev.botta.trantor.ai.errors.NoObjectGeneratedError
import dev.botta.trantor.ai.generation.GenerateRequest
import dev.botta.trantor.ai.generation.NextStep
import dev.botta.trantor.ai.generation.ObjectResult
import dev.botta.trantor.ai.generation.RunResult
import dev.botta.trantor.ai.generation.RunStream
import dev.botta.trantor.ai.generation.StepSetup
import dev.botta.trantor.ai.generation.ToolLoop
import dev.botta.trantor.ai.history.projected
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.ChatRequest
import dev.botta.trantor.ai.models.chat.OutputSpec
import dev.botta.trantor.ai.models.chat.objectAs
import dev.botta.trantor.ai.schemas.JsonSchemas
import dev.botta.trantor.ai.tools.ToolErrorHandlers
import dev.botta.trantor.ai.telemetry.AITelemetrySettings
import io.opentelemetry.api.OpenTelemetry
import kotlinx.serialization.KSerializer
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.ai.tools.defaultJsonSerializer

/**
 * [AI] over the models of the [ModelRegistry], running every generation on the [ToolLoop]. Each step sends what the
 * context policies of the request leave of the conversation, and a run that ended well is kept in its session.
 *
 * With an [openTelemetry] that exports, every generation is traced: its calls to the model and its tools, under the
 * span that was current when it was called. `addAI` passes the one of the container.
 */
class DefaultAI(
    private val models: ModelRegistry,
    private val errorHandlers: ToolErrorHandlers = ToolErrorHandlers(),
    private val openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
    /** Whether the spans carry what was said, which they do not unless asked. */
    private val telemetrySettings: AITelemetrySettings = AITelemetrySettings(),
    /** The serializer of the application, which reads and describes the args of the tools. */
    private val serializer: JsonSerializer = defaultJsonSerializer,
): AI {
    override fun text(prompt: String, model: String?, options: CallOptions) =
        generate(GenerateRequest().model(model).user(prompt).callOptions(options)).text

    override fun generate(request: GenerateRequest) = run(request, request.toChatRequest())

    override fun <T> generateObject(request: GenerateRequest, serializer: KSerializer<T>): ObjectResult<T> {
        // Into a request of its own, so that a builder the application reuses does not keep asking for this type
        val output = OutputSpec.Json(JsonSchemas.of(serializer.descriptor))
        val run = run(request, request.toChatRequest().copy(output = output))

        return try {
            ObjectResult(run.response.objectAs(serializer), run)
        } catch (e: NoObjectGeneratedError) {
            ObjectResult(null, run, e)
        }
    }

    override fun stream(request: GenerateRequest): RunStream {
        val stream = loopFor(request).stream(request.toChatRequest(), request.callOptions, request.decisions.toList())

        return if (request.session == null && request.compaction == null) stream else KeptStream(stream, request)
    }

    override fun models() = models

    private fun run(request: GenerateRequest, first: ChatRequest) =
        request.compacted(loopFor(request).run(first, request.callOptions, request.decisions.toList()))
            .also(request::keep)

    private fun loopFor(request: GenerateRequest): ToolLoop {
        val model = request.model?.let { models.chat(it) } ?: models.chat()
        val tools = request.tools.toList()
        val policies = request.contextPolicies.toList()
        val next = NextStep { chat, _ ->
            StepSetup(model, chat.copy(messages = projected(policies, chat.messages, request.context)), tools)
        }

        return ToolLoop(
            next, request.maxSteps, request.context, errorHandlers.all, openTelemetry, telemetrySettings, serializer,
        )
    }

    /**
     * A stream whose conversation is compacted and kept in its session once it is read to its end, and not when it is
     * closed before.
     */
    private class KeptStream(private val stream: RunStream, private val request: GenerateRequest): RunStream {
        private var kept: RunResult? = null
        private var closed = false

        override fun hasNext(): Boolean {
            if (closed) return false
            if (stream.hasNext()) return true

            if (kept == null) kept = request.compacted(stream.result()).also(request::keep)

            return false
        }

        override fun next() = stream.next()

        override fun result(): RunResult {
            while (hasNext()) next()

            return kept ?: stream.result()
        }

        override fun close() {
            closed = true
            stream.close()
        }
    }
}
