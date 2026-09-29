package dev.botta.trantor.ai.providers.openai

import dev.botta.json.Json
import dev.botta.json.values.JsonArray
import dev.botta.json.values.JsonObject
import dev.botta.json.values.JsonValue
import dev.botta.trantor.ai.errors.InvalidProviderOptionError
import dev.botta.trantor.ai.errors.UnsupportedRequestError
import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.catalog.*
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.providers.ProviderOptions
import dev.botta.trantor.ai.providers.RawOptions
import dev.botta.trantor.ai.schemas.StrictSchema
import dev.botta.trantor.ai.tools.*

/**
 * Turns a [ChatRequest] into the body of a call to the OpenAI Responses API.
 *
 * **A setting the model does not take is dropped with a warning, never sent.** The split that matters here cuts
 * both ways: a reasoning model refuses a temperature that is not its own — *"Unsupported value: 'temperature' does
 * not support 0.2 with this model"* — and a model that does not reason refuses `reasoning`. What each one takes
 * comes from the [ModelCatalog]. A model nobody described is taken to be the latest one of the provider, and
 * whatever is decided out of that guess says so in its warning.
 *
 * The mapper has no state of its own: what a mapping collects on the way, like the warnings, belongs to that
 * mapping. A model is shared by everyone who asks the registry for it, and calls run on several threads.
 */
internal class OpenAIRequestMapper(
    private val config: OpenAIConfig = OpenAIConfig(apiKey = ""),
    private val catalog: ModelCatalog = ModelCatalog().addOpenAIModels(),
) {
    fun map(modelId: String, request: ChatRequest, stream: Boolean = false) =
        Mapping(catalog.find(OPENAI_PROVIDER, modelId), modelId).map(modelId, request, stream)

    /** [spec] is null when the provider has no latest set either, and then nothing here holds anything back. */
    private inner class Mapping(private val spec: ModelSpec?, private val modelId: String) {
        private val warnings = mutableListOf<ModelWarning>()
        private val model: ModelCapabilities? = spec?.capabilities

        private val takesReasoning get() = model == null || model.reasoningEfforts.isNotEmpty()
        private val reasons get() = model != null && model.reasoningEfforts.isNotEmpty()

        /**
         * A reasoning model refuses every sampling setting **while it is reasoning**, which is the one capability
         * that is not a property of the model but of how two settings meet. The GPT-5.x families can be told to
         * stop reasoning and then they take them again; GPT-6 and the o-series cannot be told that at all.
         */
        private fun takesSamplingSettings(reasoning: Reasoning?) = when {
            model == null -> true
            model.temperature == null -> false
            !reasons -> true
            else -> reasoning == Reasoning.Off && ModelFeatures.ReasoningOff in model
        }

        fun map(modelId: String, request: ChatRequest, stream: Boolean = false): MappedRequest {
            // OpenAI takes a system message anywhere, and the one that changes goes last so the rest stays cached
            val dynamic = request.dynamicSystem?.let { Message.System(it) }
            val body = Json.obj(
                "model" to modelId,
                "input" to Json.array((request.messages + listOfNotNull(dynamic)).flatMap { toItems(it) }),
            )

            if (stream) body["stream"] = true

            toOutputFormat(request.output)?.let { body["text"] = Json.obj("format" to it) }

            if (request.tools.isNotEmpty()) {
                body["tools"] = Json.array(request.tools.mapNotNull { toTool(it) })
                body["tool_choice"] = toToolChoice(request.toolChoice)
            }

            with(request.settings) {
                maxOutputTokens?.let { body["max_output_tokens"] = it }
                parallelToolCalls?.let { body["parallel_tool_calls"] = it }
                stopSequences?.let { unsupportedSetting("stopSequences") }
                seed?.let { unsupportedSetting("seed") }

                // A reasoning model answers 400 to any temperature but its own, and so does top_p
                if (takesSamplingSettings(reasoning)) {
                    temperature?.let { body["temperature"] = coerced(it, "temperature", model?.temperature) }
                    topP?.let { body["top_p"] = coerced(it, "topP", model?.topP) }
                } else {
                    temperature?.let { droppedByTheModel("temperature") }
                    topP?.let { droppedByTheModel("topP") }
                }

                // And the other half of the same split: a model that does not reason refuses being asked to
                if (reasoning != null && !takesReasoning) {
                    droppedByTheModel("reasoning")
                } else {
                    reasoning?.let { toReasoning(it) }?.let {
                        body["reasoning"] = it
                        // Without this the reasoning of this turn cannot be sent back on the next one
                        if (it["effort"]?.asString() != NO_EFFORT) body["include"] = Json.array(ENCRYPTED_REASONING)
                    }
                }
            }

            config.store?.let { body["store"] = it }

            applyOptions(body, request.providerOptions)

            if (request.settings.failOnWarnings && warnings.isNotEmpty()) {
                throw UnsupportedRequestError(OPENAI_PROVIDER, warnings.toList())
            }

            return MappedRequest(body, warnings.toList())
        }

        private fun applyOptions(body: JsonObject, options: ProviderOptions) {
            options.providers.filter { it != OPENAI_PROVIDER }.forEach {
                warnings.add(ModelWarning("Options for $it are not OpenAI options and were dropped"))
            }

            val ours = options.forProvider(OPENAI_PROVIDER)

            // Typed options first, so that what counts as a conflict for a raw one doesn't depend on the order they came in
            ours.filterIsInstance<OpenAIOptions>().forEach { apply(body, it) }
            ours.filterIsInstance<RawOptions>().forEach { merge(body, it.values) }
            ours.filter { it !is OpenAIOptions && it !is RawOptions }.forEach {
                warnings.add(ModelWarning("${it::class.simpleName} is not an option this adapter knows"))
            }
        }

        private fun apply(body: JsonObject, options: OpenAIOptions) {
            options.serviceTier?.let { body["service_tier"] = it.name.lowercase() }
            options.store?.let { body["store"] = it }
            options.promptCacheKey?.let { body["prompt_cache_key"] = it }
            options.safetyIdentifier?.let { body["safety_identifier"] = it }
            options.truncation?.let { body["truncation"] = it.name.lowercase() }
            // Verbosity travels inside text, next to the output format
            options.verbosity?.let { textOf(body)["verbosity"] = it.name.lowercase() }
        }

        private fun textOf(body: JsonObject) = body["text"]?.asObject() ?: Json.obj().also { body["text"] = it }

        /**
         * Raw options go in as they are, and a value the adapter already filled in is a conflict rather than an
         * override: silently replacing the input, the tools or a setting would send something the caller did not
         * write.
         *
         * The merge goes all the way down, so a new field can be added next to one the adapter wrote — another
         * key inside `text`, another entry in `include` — and only the value that is really in the way fails.
         */
        private fun merge(body: JsonObject, values: JsonObject, path: String = "") {
            values.keys.forEach { key ->
                val here = if (path.isEmpty()) key else "$path.$key"
                val mine = body[key]
                val theirs = values.getValue(key)

                when {
                    mine == null -> body[key] = theirs
                    mine is JsonObject && theirs is JsonObject -> merge(mine, theirs, here)
                    mine is JsonArray && theirs is JsonArray -> theirs.forEach { if (mine.none { own -> own == it }) mine.add(it) }
                    else -> throw InvalidProviderOptionError(
                        OPENAI_PROVIDER,
                        here,
                        "Raw option $here is already set by the adapter. Use the setting that fills it instead.",
                    )
                }
            }
        }

        private fun coerced(asked: Double, setting: String, range: ValueRange?): Double {
            val sent = range?.coerce(asked) ?: asked

            if (sent != asked) {
                warnings.add(
                    ModelWarning(
                        "$modelId takes a $setting between ${range!!.min} and ${range.max}, " +
                            "so $asked was sent as $sent",
                        setting,
                    )
                )
            }

            return sent
        }

        private fun droppedByTheModel(setting: String) {
            warnings.add(ModelWarning("$modelId does not take $setting, so it was not sent$becauseItIsAGuess", setting))
        }

        /**
         * A decision taken from a guess says so. The catalog is standing in the newest model it knows for one
         * nobody described, which is right far more often than not and wrong in a way a written entry never is.
         */
        private val becauseItIsAGuess
            get() = if (spec?.isGuess != true) "" else
                ". That is what the newest model in the catalog takes; add $modelId to it if it takes more"

        /** Null when there is nothing to ask for, so that [Reasoning.Off] and no reasoning at all mean the same. */
        private fun toReasoning(reasoning: Reasoning): JsonObject? {
            if (reasoning.budgetTokens != null) unsupportedSetting("reasoning.budgetTokens")

            // Off is a level of its own here, and asking for it out loud is what lets a temperature through
            if (reasoning == Reasoning.Off && model != null && ModelFeatures.ReasoningOff in model) {
                return Json.obj("effort" to NO_EFFORT)
            }

            val effort = reasoning.effort?.let { nearest(it) }?.name?.lowercase()
            val summary = when (reasoning.summary) {
                ReasoningSummaries.None -> null
                ReasoningSummaries.Auto -> "auto"
                ReasoningSummaries.Detailed -> "detailed"
            }

            if (effort == null && summary == null) return null

            return Json.obj().apply {
                effort?.let { this["effort"] = it }
                summary?.let { this["summary"] = it }
            }
        }

        /**
         * The closest level the model does have, which is the one below what was asked before the one above it:
         * thinking a little less than the caller wanted is cheaper than thinking a lot more. `minimal` is the one
         * that moves — only the first GPT-5 family has it.
         */
        private fun nearest(asked: ReasoningEfforts): ReasoningEfforts? {
            val available = model?.reasoningEfforts?.takeIf { it.isNotEmpty() } ?: return asked

            if (asked in available) return asked

            val sent = available.filter { it < asked }.maxOrNull() ?: available.minOrNull()

            sent?.let {
                warnings.add(ModelWarning("$modelId has no $asked effort, so it was asked for as $it", "reasoning"))
            }

            return sent
        }

        private fun toOutputFormat(output: OutputSpec) = when (output) {
            is OutputSpec.Text -> null
            is OutputSpec.Json -> Json.obj(
                "type" to "json_schema",
                "name" to output.name,
                "strict" to output.strict,
                "schema" to schemaFor(output.schema, output.strict),
            )
        }

        // In strict mode the schema has to follow rules a plain schema doesn't, so the adapter fixes it instead of
        // making everyone write it by hand
        private fun schemaFor(schema: JsonObject, strict: Boolean) =
            if (strict) StrictSchema.of(schema, OpenAIStrictRules) else schema

        private fun toTool(tool: ToolSpec) = when (tool) {
            is FunctionToolSpec -> Json.obj(
                "type" to "function",
                "name" to tool.name,
                "description" to tool.description,
                "strict" to tool.strict,
                "parameters" to schemaFor(tool.parameters, tool.strict),
            )
            is ProviderToolSpec -> if (tool.name.startsWith("$OPENAI_PROVIDER.")) {
                tool.args.with("type", tool.name.removePrefix("$OPENAI_PROVIDER."))
            } else {
                warnings.add(ModelWarning("Tool ${tool.name} is not an OpenAI tool and was dropped"))
                null
            }
        }

        private fun toToolChoice(choice: ToolChoice): JsonValue = when (choice) {
            is ToolChoice.Auto -> Json.value("auto")
            is ToolChoice.None -> Json.value("none")
            is ToolChoice.Required -> Json.value("required")
            is ToolChoice.Named -> Json.obj("type" to "function", "name" to choice.name)
        }

        private fun toItems(message: Message): List<JsonObject> = when (message) {
            is Message.System -> listOf(Json.obj("type" to "message", "role" to "system", "content" to message.text))
            is Message.User -> toMessageItems("user", message.parts)
            is Message.Assistant -> toMessageItems("assistant", message.parts)
            is Message.Tool -> message.results.map { toToolResultItem(it) }
            is Message.Summary -> message.toldByTheUser()?.let { toMessageItems("user", it.parts) }
                ?: emptyList<JsonObject>().also { warnings.add(ModelWarning(UNREADABLE_SUMMARY)) }
        }

        /**
         * Content parts travel inside a message item and everything else is an item of its own, keeping the order they
         * had in the message: a reasoning item has to reach OpenAI before the message it belongs to.
         */
        private fun toMessageItems(role: String, parts: List<Part>): List<JsonObject> {
            val items = mutableListOf<JsonObject>()
            var content: JsonArray? = null

            parts.forEach { part ->
                when (part) {
                    is TextPart -> contentOf(items, role, content).let {
                        content = it
                        it.add(toTextPart(role, part))
                    }
                    // Content of a message goes back inside a message; an item of its own goes back as an item
                    is ProviderPart if part.wasInsideAMessage -> contentOf(items, role, content).let {
                        content = it
                        if (part.provider == OPENAI_PROVIDER) it.add(part.raw) else unsupportedPart(part)
                    }
                    is ToolCallPart -> {
                        content = null
                        items.add(toToolCallItem(part))
                    }
                    is ToolResultPart -> {
                        content = null
                        items.add(toToolResultItem(part))
                    }
                    // An item we didn't model when we received it goes back exactly as it came
                    is ProviderPart -> {
                        content = null
                        if (part.provider == OPENAI_PROVIDER) items.add(part.raw) else unsupportedPart(part)
                    }
                    // Reasoning is signed by whoever produced it, so only its own provider can take it back
                    is ReasoningPart -> {
                        content = null
                        val item = part.opaque?.takeIf { part.metadata[OPENAI_PROVIDER] != null }

                        if (item != null) items.add(item) else foreignReasoning()
                    }
                    else -> unsupportedPart(part)
                }
            }

            return items
        }

        private fun contentOf(items: MutableList<JsonObject>, role: String, content: JsonArray?): JsonArray {
            content?.let { return it }

            return Json.array().also { items.add(Json.obj("type" to "message", "role" to role, "content" to it)) }
        }

        /** Whatever the provider attached to the text travels back with it. */
        private fun toTextPart(role: String, part: TextPart): JsonObject {
            val json = Json.obj("type" to textType(role), "text" to part.text)

            part.metadata[OPENAI_PROVIDER]?.keys?.forEach { json[it] = part.metadata[OPENAI_PROVIDER]!!.getValue(it) }

            return json
        }

        private fun textType(role: String) = if (role == "assistant") "output_text" else "input_text"

        // Arguments and output travel as strings, not as objects
        private fun toToolCallItem(part: ToolCallPart) = Json.obj(
            "type" to "function_call",
            "call_id" to part.callId,
            "name" to part.toolName,
            "arguments" to part.input.toString(),
        )

        private fun toToolResultItem(part: ToolResultPart) = Json.obj(
            "type" to "function_call_output",
            "call_id" to part.callId,
            "output" to when (val output = part.output) {
                is ToolOutput.Text -> output.value
                is ToolOutput.Json -> output.value.toString()
            },
        )

        private fun foreignReasoning() {
            warnings.add(ModelWarning("Reasoning that OpenAI did not produce was dropped"))
        }

        private fun unsupportedPart(part: Part) {
            warnings.add(ModelWarning("${part::class.simpleName} is not sent to OpenAI yet and was dropped"))
        }

        private fun unsupportedSetting(setting: String) {
            warnings.add(ModelWarning("The OpenAI Responses API does not support $setting", setting))
        }
    }
}

internal data class MappedRequest(val body: JsonObject, val warnings: List<ModelWarning>)

private const val ENCRYPTED_REASONING = "reasoning.encrypted_content"

private const val NO_EFFORT = "none"
