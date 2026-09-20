package dev.botta.trantor.ai.providers.openai

import dev.botta.trantor.ai.models.catalog.ModelCapabilities
import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.ai.models.catalog.ModelFeatures.*
import dev.botta.trantor.ai.models.catalog.ValueRange
import dev.botta.trantor.ai.models.chat.ReasoningEfforts.*

/**
 * What each family of OpenAI takes. The split that matters is reasoning against the rest, and it cuts both ways.
 *
 * A reasoning model refuses a temperature that is not its own, with
 * *"Unsupported value: 'temperature' does not support 0.2 with this model. Only the default (1) value is
 * supported."* — the same failure that is open as a bug in LiteLLM, LibreChat and half a dozen others. A model
 * that does not reason refuses `reasoning` instead.
 *
 * No ceiling is written here. Unlike Anthropic, the Responses API does not require `max_output_tokens`, so there
 * is no field to fill in and nothing to guess: a call that asks for more than the model gives is answered by
 * OpenAI.
 */
internal fun ModelCatalog.addOpenAIModels() = apply {
    // Minimal exists on the GPT-5 family and nowhere else
    add(
        "openai/gpt-5",
        "openai/gpt-5-mini",
        "openai/gpt-5-nano",
        capabilities = reasoning.copy(reasoningEfforts = setOf(Minimal, Low, Medium, High)),
    )

    add("openai/o1", "openai/o3", "openai/o3-mini", "openai/o4-mini", capabilities = reasoning)

    add("openai/gpt-4.1", "openai/gpt-4.1-mini", "openai/gpt-4.1-nano", capabilities = sampling)
    add("openai/gpt-4o", "openai/gpt-4o-mini", capabilities = sampling)

    // Before the structured output of json_schema
    add(
        "openai/gpt-4",
        "openai/gpt-4-turbo",
        "openai/gpt-3.5-turbo",
        capabilities = sampling.copy(features = sampling.features - StructuredOutput),
    )
}

/** Takes an effort and refuses every sampling setting. */
private val reasoning = ModelCapabilities(
    temperature = null,
    topP = null,
    reasoningEfforts = setOf(Low, Medium, High),
    features = setOf(Tools, StructuredOutput, Images, PromptCaching),
)

/** Takes the sampling settings and refuses `reasoning`, which is the other half of the same split. */
private val sampling = reasoning.copy(
    temperature = ValueRange.ZeroToTwo,
    topP = ValueRange.ZeroToOne,
    reasoningEfforts = emptySet(),
)
