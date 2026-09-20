package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.Json
import dev.botta.json.values.JsonArray
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.errors.InvalidProviderOptionError
import dev.botta.trantor.ai.errors.UnsupportedRequestError
import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.catalog.*
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.providers.ProviderOptions
import dev.botta.trantor.ai.providers.RawOptions
import dev.botta.trantor.ai.schemas.StrictSchema

/**
 * Turns a [ChatRequest] into the body of a call to the Anthropic Messages API.
 *
 * **A setting the model does not take is dropped with a warning, never sent.** Anthropic answers 400 to a field a
 * model stopped accepting, and the ones that moved between generations are the everyday ones: `temperature`, the
 * thinking budget, the json schema. Sending them blindly would make the plain api work on some models and fail on
 * others, which is the opposite of what it is for.
 *
 * What each model takes comes from the [ModelCatalog]. A model nobody described is taken to be the latest one
 * of the provider, and whatever is decided out of that guess says so in its warning. [AnthropicOptions] is the
 * way out for somebody who wants to send something regardless of what the catalog says.
 *
 * The mapper has no state of its own: what a mapping collects on the way, like the warnings, belongs to that
 * mapping. A model is shared by everyone who asks the registry for it, and calls run on several threads.
 */
internal class AnthropicRequestMapper(
    private val config: AnthropicConfig = AnthropicConfig(apiKey = ""),
    private val catalog: ModelCatalog = ModelCatalog().addAnthropicModels(),
) {
    fun map(modelId: String, request: ChatRequest, stream: Boolean = false) =
        Mapping(catalog.find(ANTHROPIC_PROVIDER, modelId), modelId).map(modelId, request, stream)

    /** [spec] is null when the provider has no latest set either, and then nothing here holds anything back. */
    private inner class Mapping(private val spec: ModelSpec?, private val modelId: String) {
        private val warnings = mutableListOf<ModelWarning>()
        private val model: ModelCapabilities? = spec?.capabilities

        private val takesSamplingSettings get() = model == null || model.temperature != null
        private val takesEffort get() = model == null || model.reasoningEfforts.isNotEmpty()
        private val takesBudget get() = model == null || model.reasoningBudget != null
        private val takesStructuredOutput get() = model == null || ModelFeatures.StructuredOutput in model

        fun map(modelId: String, request: ChatRequest, stream: Boolean = false): MappedRequest {
            val options = anthropicOptionsOf(request.providerOptions)
            val thinking = toThinking(request.settings.reasoning, options)
            val body = Json.obj(
                "model" to modelId,
                "messages" to Json.array(toMessages(request.messages)),
            )

            systemOf(request.messages)?.let { body["system"] = it }
            // Required by the api, unlike everywhere else, so there is always a number to send
            body["max_tokens"] = maxTokensFor(request.settings, thinking)
            thinking?.let { body["thinking"] = it }

            if (stream) body["stream"] = true

            applySettings(body, request.settings)
            applyOutputConfig(body, request.output, request.settings.reasoning, options)
            applyCache(body, options)

            options?.userId?.let { body["metadata"] = Json.obj("user_id" to it) }
            options?.serviceTier?.let { body["service_tier"] = it.wireName }

            applyRawOptions(body, request.providerOptions)

            if (request.settings.failOnWarnings && warnings.isNotEmpty()) {
                throw UnsupportedRequestError(ANTHROPIC_PROVIDER, warnings.toList())
            }

            return MappedRequest(body, warnings.toList())
        }

        private fun applySettings(body: JsonObject, settings: ChatSettings): Unit = with(settings) {
            stopSequences?.let { body["stop_sequences"] = Json.array(it.map { stop -> Json.value(stop) }) }
            seed?.let { unsupportedSetting("seed") }

            if (temperature == null && topP == null) return

            // Deprecated from Claude Opus 4.6 on: the models after it answer 400 to anything but the default
            if (!takesSamplingSettings) {
                temperature?.let { droppedByTheModel("temperature") }
                topP?.let { droppedByTheModel("topP") }
                return
            }

            // Anthropic tops out at 1, where OpenAI goes to 2, so the same setting means a failed call here
            temperature?.let { body["temperature"] = coerced(it, "temperature", model?.temperature) }
            topP?.let { body["top_p"] = coerced(it, "topP", model?.topP) }

            return
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

        /**
         * `max_tokens` is the ceiling of the whole answer, thinking included, so a budget is added on top of the
         * room the caller wanted for the text. Nobody asking for a number means everything the model gives, and
         * there the budget is already inside that ceiling rather than on top of it — adding it would ask for more
         * than the model has and turn thinking into a warning on every call.
         *
         * Whatever is asked for above what the model can give is a 400, so it is brought back down to the ceiling.
         */
        private fun maxTokensFor(settings: ChatSettings, thinking: JsonObject?): Int {
            val ceiling = model?.maxOutputTokens
            val asked = settings.maxOutputTokens ?: config.defaultMaxTokens ?: return ceiling ?: guessedCeiling()
            val budget = thinking?.get("budget_tokens")?.asInt() ?: 0
            val total = asked + budget

            if (ceiling == null || total <= ceiling) return total

            warnings.add(
                ModelWarning(
                    "$total output tokens is more than the $ceiling $modelId gives, so the call asks for $ceiling",
                    "maxOutputTokens",
                )
            )

            return ceiling
        }

        /**
         * The one place a value is invented for a model nobody described: the api demands `max_tokens` and there
         * is nothing to derive it from. It is small on purpose, and it is said out loud.
         */
        private fun guessedCeiling(): Int {
            warnings.add(
                ModelWarning(
                    "$modelId is not in the model catalog, so the call asks for $FALLBACK_MAX_TOKENS output tokens. " +
                        "Add it to the catalog, or set maxOutputTokens.",
                    "maxOutputTokens",
                )
            )

            return FALLBACK_MAX_TOKENS
        }

        /**
         * Thinking, in whichever of the two shapes the model takes. A level is the portable way of asking, so it
         * becomes an effort where there is one and a budget where there isn't; a budget is the exact way of asking,
         * and it has nowhere to go on a model that only takes levels.
         */
        private fun toThinking(reasoning: Reasoning?, options: AnthropicOptions?): JsonObject? {
            options?.thinking?.let { return toThinking(it) }
            if (reasoning == null) return null
            if (reasoning == Reasoning.Off) return Json.obj("type" to "disabled")

            reasoning.budgetTokens?.let {
                if (takesBudget) return budgetThinking(it, reasoning.summary)

                droppedByTheModel("reasoning.budgetTokens")
                return null
            }

            if (reasoning.effort == null) return null
            if (takesEffort) return adaptiveThinking(reasoning.summary)
            if (takesBudget) return budgetThinking(budgetFor(reasoning.effort), reasoning.summary)

            droppedByTheModel("reasoning")

            return null
        }

        private fun toThinking(thinking: AnthropicThinking) = when (thinking) {
            is AnthropicThinking.Off -> Json.obj("type" to "disabled")
            is AnthropicThinking.Adaptive -> adaptiveThinking(thinking.summary)
            is AnthropicThinking.Budget -> budgetThinking(thinking.tokens, thinking.summary)
        }

        private fun adaptiveThinking(summary: ReasoningSummaries) =
            adaptiveThinking(summary != ReasoningSummaries.None)

        private fun adaptiveThinking(summary: Boolean) =
            Json.obj("type" to "adaptive", "display" to displayFor(summary))

        private fun budgetThinking(tokens: Int, summary: ReasoningSummaries) =
            budgetThinking(tokens, summary != ReasoningSummaries.None)

        private fun budgetThinking(tokens: Int, summary: Boolean): JsonObject {
            val range = model?.reasoningBudget

            return Json.obj(
                "type" to "enabled",
                // The api refuses anything under a thousand, and a budget over the ceiling leaves no room to answer
                "budget_tokens" to if (range == null) tokens else tokens.coerceIn(range.first, range.last),
            ).also { if (!summary) it["display"] = displayFor(false) }
        }

        /** Omitted still returns the signature, which is what carries the thinking to the next turn. */
        private fun displayFor(summary: Boolean) = if (summary) "summarized" else "omitted"

        /**
         * A share of what the model can give, which is how a level becomes a number of tokens on a model that
         * only takes a budget. The shares are the ones the Vercel AI SDK settled on, and they are a policy rather
         * than a fact: [AnthropicOptions.thinking] is there for whoever wants to say the number.
         */
        private fun budgetFor(effort: ReasoningEfforts) = when (effort) {
            ReasoningEfforts.Minimal -> 0.02
            ReasoningEfforts.Low -> 0.10
            ReasoningEfforts.Medium -> 0.30
            ReasoningEfforts.High -> 0.60
        }.let { ((model?.maxOutputTokens ?: FALLBACK_MAX_TOKENS) * it).toInt() }

        /** Effort and the output format share one field, so they are filled together. */
        private fun applyOutputConfig(
            body: JsonObject,
            output: OutputSpec,
            reasoning: Reasoning?,
            options: AnthropicOptions?,
        ) {
            val outputConfig = Json.obj()

            effortFor(reasoning, options)?.let { outputConfig["effort"] = it }
            formatFor(output)?.let { outputConfig["format"] = it }

            if (outputConfig.keys.isNotEmpty()) body["output_config"] = outputConfig
        }

        private fun effortFor(reasoning: Reasoning?, options: AnthropicOptions?): String? {
            options?.effort?.let { return it.wireName }
            if (options?.thinking != null) return null

            val asked = reasoning?.effort?.takeIf { takesEffort } ?: return null
            val sent = nearest(asked) ?: return null

            if (sent != asked) {
                warnings.add(ModelWarning("$modelId has no $asked effort, so it was asked for as $sent", "reasoning"))
            }

            return sent.name.lowercase()
        }

        /**
         * The closest level the model does have, which is the one below what was asked before the one above it:
         * thinking a little less than the caller wanted is cheaper than thinking a lot more.
         */
        private fun nearest(asked: ReasoningEfforts): ReasoningEfforts? {
            val available = model?.reasoningEfforts ?: return asked

            if (asked in available) return asked

            return available.filter { it < asked }.maxOrNull() ?: available.minOrNull()
        }

        private fun formatFor(output: OutputSpec) = when (output) {
            is OutputSpec.Text -> null
            is OutputSpec.Json -> if (takesStructuredOutput) {
                // Anthropic names no schema and takes no strict flag: closing the schema is the whole of it
                Json.obj("type" to "json_schema", "schema" to StrictSchema.of(output.schema))
            } else {
                droppedByTheModel("output")
                null
            }
        }

        /**
         * The top-level mark, which is Anthropic's own automatic mode: it puts the cut on the last cacheable block
         * and moves it forward as the conversation grows. A cut somewhere precise is marked on the part instead.
         */
        private fun applyCache(body: JsonObject, options: AnthropicOptions?) {
            when (options?.cache ?: config.cache) {
                AnthropicCaches.Off -> return
                AnthropicCaches.Automatic -> body["cache_control"] = Json.obj("type" to "ephemeral")
                AnthropicCaches.AutomaticForAnHour ->
                    body["cache_control"] = Json.obj("type" to "ephemeral", "ttl" to "1h")
            }
        }

        private fun anthropicOptionsOf(options: ProviderOptions): AnthropicOptions? {
            options.providers.filter { it != ANTHROPIC_PROVIDER }.forEach {
                warnings.add(ModelWarning("Options for $it are not Anthropic options and were dropped"))
            }

            val ours = options.forProvider(ANTHROPIC_PROVIDER)

            ours.filter { it !is AnthropicOptions && it !is RawOptions }.forEach {
                warnings.add(ModelWarning("${it::class.simpleName} is not an option this adapter knows"))
            }

            return ours.filterIsInstance<AnthropicOptions>().firstOrNull()
        }

        private fun applyRawOptions(body: JsonObject, options: ProviderOptions) {
            options.forProvider(ANTHROPIC_PROVIDER).filterIsInstance<RawOptions>().forEach { merge(body, it.values) }
        }

        /**
         * Raw options go in as they are, and a value the adapter already filled in is a conflict rather than an
         * override: silently replacing the messages, the tools or a setting would send something the caller did
         * not write.
         *
         * The merge goes all the way down, so a new field can be added next to one the adapter wrote — another key
         * inside `output_config` — and only the value that is really in the way fails.
         */
        private fun merge(body: JsonObject, values: JsonObject, path: String = "") {
            values.keys.forEach { key ->
                val here = if (path.isEmpty()) key else "$path.$key"
                val mine = body[key]
                val theirs = values.getValue(key)

                when {
                    mine == null -> body[key] = theirs
                    mine is JsonObject && theirs is JsonObject -> merge(mine, theirs, here)
                    mine is JsonArray && theirs is JsonArray ->
                        theirs.forEach { if (mine.none { own -> own == it }) mine.add(it) }
                    else -> throw InvalidProviderOptionError(
                        ANTHROPIC_PROVIDER,
                        here,
                        "Raw option $here is already set by the adapter. Use the setting that fills it instead.",
                    )
                }
            }
        }

        /**
         * The system prompt is a field of the request and not a message, so the first one ends up there wherever it
         * was written. What happens to the rest is a trade-off between reach and cache, which is why it is a
         * setting: joined into the field they work on every model, and left in place they keep the cached prefix
         * intact but only the newest models take them.
         */
        private fun systemOf(messages: List<Message>): String? {
            val texts = messages.filterIsInstance<Message.System>().map { it.text }

            if (config.midConversationSystemMessages) return texts.firstOrNull()

            if (texts.size > 1) {
                warnings.add(
                    ModelWarning("Anthropic takes one system prompt, so the ${texts.size} of them were joined")
                )
            }

            return texts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
        }

        /**
         * Anthropic wants the roles to alternate, so two messages of the same role in a row become one message
         * with the content of both. A conversation built turn by turn — an assistant message and the reasoning
         * that came with it, a tool result after another — is otherwise rejected before the model sees it.
         */
        private fun toMessages(messages: List<Message>): List<JsonObject> {
            val result = mutableListOf<JsonObject>()
            val firstSystem = messages.indexOfFirst { it is Message.System }

            messages.forEachIndexed { index, message ->
                val role = roleOf(message, isTheSystemPrompt = index == firstSystem) ?: return@forEachIndexed
                val content = toContent(message)

                if (content.isEmpty()) return@forEachIndexed

                val last = result.lastOrNull()

                if (last?.get("role")?.asString() == role) {
                    content.forEach { last["content"]!!.asArray()!!.add(it) }
                } else {
                    result.add(Json.obj("role" to role, "content" to Json.array(content)))
                }
            }

            return result
        }

        /** Null for whatever went into the system field, which is not a message here. */
        private fun roleOf(message: Message, isTheSystemPrompt: Boolean) = when (message) {
            is Message.System ->
                if (isTheSystemPrompt || !config.midConversationSystemMessages) null else "system"
            is Message.User -> "user"
            is Message.Assistant -> "assistant"
            // A tool result is something the model is told, so Anthropic reads it as a turn of the user
            is Message.Tool -> "user"
        }

        private fun toContent(message: Message): List<JsonObject> = when (message) {
            is Message.System -> listOf(Json.obj("type" to "text", "text" to message.text))
            is Message.User -> message.parts.mapNotNull { toBlock(it) }
            is Message.Assistant -> message.parts.mapNotNull { toBlock(it) }
            is Message.Tool -> message.results.mapNotNull { toBlock(it) }
        }

        private fun toBlock(part: Part): JsonObject? = when (part) {
            is TextPart -> withMetadata(Json.obj("type" to "text", "text" to part.text), part)
            // A block we did not model when we received it goes back exactly as it came
            is ProviderPart -> if (part.provider == ANTHROPIC_PROVIDER) part.raw else unsupportedPart(part)
            // Thinking is signed by whoever produced it, so only its own provider can take it back
            is ReasoningPart -> part.opaque?.takeIf { part.metadata[ANTHROPIC_PROVIDER] != null } ?: foreignReasoning()
            else -> unsupportedPart(part)
        }

        private fun foreignReasoning(): JsonObject? {
            warnings.add(ModelWarning("Reasoning that Anthropic did not produce was dropped"))

            return null
        }

        /** Whatever the provider attached to the part travels back with it: citations and cache marks, for two. */
        private fun withMetadata(json: JsonObject, part: Part): JsonObject {
            val extras = part.metadata[ANTHROPIC_PROVIDER] ?: return json

            extras.keys.forEach { json[it] = extras.getValue(it) }

            return json
        }

        private fun unsupportedPart(part: Part): JsonObject? {
            warnings.add(ModelWarning("${part::class.simpleName} is not sent to Anthropic yet and was dropped"))

            return null
        }

        private fun unsupportedSetting(setting: String) {
            warnings.add(ModelWarning("The Anthropic Messages API does not support $setting", setting))
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
    }
}

internal data class MappedRequest(val body: JsonObject, val warnings: List<ModelWarning>)

/** Only reached by a model nobody described, where there is nothing to derive a ceiling from. */
private const val FALLBACK_MAX_TOKENS = 4_096

private val AnthropicEfforts.wireName get() = name.lowercase()

private val ServiceTiers.wireName
    get() = if (this == ServiceTiers.StandardOnly) "standard_only" else name.lowercase()
