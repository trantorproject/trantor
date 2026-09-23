package dev.botta.trantor.ai

import dev.botta.trantor.ai.errors.NoObjectGeneratedError
import dev.botta.trantor.ai.generation.GenerateRequest
import dev.botta.trantor.ai.generation.ObjectResult
import dev.botta.trantor.ai.generation.RunResult
import dev.botta.trantor.ai.generation.ToolLoop
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.ChatRequest
import dev.botta.trantor.ai.models.chat.OutputSpec
import dev.botta.trantor.ai.models.chat.objectAs
import dev.botta.trantor.ai.schemas.JsonSchemas
import dev.botta.trantor.ai.tools.ToolErrorHandlers
import kotlinx.serialization.KSerializer

/** [AI] over the models of the [ModelRegistry], running every generation on the [ToolLoop]. */
class DefaultAI(
    private val models: ModelRegistry,
    private val errorHandlers: ToolErrorHandlers = ToolErrorHandlers(),
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

    override fun stream(request: GenerateRequest) =
        loopFor(request).stream(request.toChatRequest(), request.callOptions)

    override fun models() = models

    private fun run(request: GenerateRequest, first: ChatRequest) =
        loopFor(request).run(first, request.callOptions)

    private fun loopFor(request: GenerateRequest): ToolLoop {
        val model = request.model?.let { models.chat(it) } ?: models.chat()

        return ToolLoop(model, request.tools.toList(), request.maxSteps, request.context, errorHandlers.all)
    }
}
