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
import dev.botta.trantor.ai.tools.search.ClientToolSearch

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
        Mapping(catalog.find(ANTHROPIC_PROVIDER, modelId), modelId, ClientToolSearch(request.tools))
            .map(modelId, request, stream)

    /** [spec] is null when the provider has no latest set either, and then nothing here holds anything back. */
    private inner class Mapping(
        private val spec: ModelSpec?,
        private val modelId: String,
        /** The search of the application among the tools of the request, which the deferred tools need. */
        private val search: ClientToolSearch,
    ) {
        private val model: ModelCapabilities? = spec?.capabilities
        private val supports = ModelSupport(spec)
        private val warnings = MappingWarnings(modelId, supports.isGuess)

        private val strictBudget = StrictBudget()
        private val thinking = AnthropicThinkingMapper(modelId, model, supports, warnings)
        private val tools = AnthropicTools(modelId, supports, warnings, search, strictBudget)

        fun map(modelId: String, request: ChatRequest, stream: Boolean = false): MappedRequest {
            val options = anthropicOptionsOf(request.providerOptions)
            val cache = options?.cache ?: config.cache
            val thinking = thinking.of(request.settings.reasoning, options)
            val conversation = AnthropicConversation(supports, warnings, cache, modelId, search)
            val body = Json.obj("model" to modelId)

            conversation.writeTo(body, request.messages, request.dynamicSystem)
            // Required by the api, unlike everywhere else, so there is always a number to send
            body["max_tokens"] = maxTokensFor(request.settings, thinking)
            thinking?.let { body["thinking"] = it }

            if (stream) body["stream"] = true

            applySettings(body, request.settings)
            // The answer in JSON before the tools: it takes its share of what can go strict, and a tool can do without
            applyOutputConfig(body, request.output, request.settings.reasoning, options)
            tools.applyTo(body, request, thinking)
            conversation.bindThinking(body)
            applyCache(body, cache, conversation)

            options?.userId?.let { body["metadata"] = Json.obj("user_id" to it) }
            options?.serviceTier?.let { body["service_tier"] = it.wireName }

            applyRawOptions(body, request.providerOptions)

            if (request.settings.failOnWarnings && warnings.isNotEmpty()) {
                throw UnsupportedRequestError(ANTHROPIC_PROVIDER, warnings.toList())
            }

            return MappedRequest(
                body,
                warnings.toList(),
                conversation.betas + this.thinking.betas,
                conversation.stamp,
                this.thinking.notes,
            )
        }

        private fun applySettings(body: JsonObject, settings: ChatSettings): Unit = with(settings) {
            stopSequences?.let { body["stop_sequences"] = Json.array(it.map { stop -> Json.value(stop) }) }
            seed?.let { warnings.unsupportedSetting("seed") }

            if (temperature == null && topP == null) return

            // Deprecated from Claude Opus 4.6 on: the models after it answer 400 to anything but the default
            if (!supports.samplingSettings) {
                temperature?.let { warnings.droppedByTheModel("temperature") }
                topP?.let { warnings.droppedByTheModel("topP") }
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

            val asked = reasoning?.effort?.takeIf { supports.effort } ?: return null
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
            is OutputSpec.Json -> if (supports.structuredOutput) {
                // Its answers in JSON are always held to their schema: there is nothing to fall back to
                if (StrictSchema.refersToItself(output.schema)) {
                    val message = "The schema of the answer refers to itself, which Anthropic cannot hold a model " +
                        "to, and it has no answer in JSON that is not held to its schema"
                    val warning = ModelWarning(message, "output")
                    throw UnsupportedRequestError(ANTHROPIC_PROVIDER, listOf(warning))
                }

                // Anthropic names no schema and takes no strict flag: closing the schema is the whole of it
                val schema = closedSchema(output.schema).also { strictBudget.spend(it) }
                Json.obj("type" to "json_schema", "schema" to schema)
            } else {
                warnings.droppedByTheModel("output")
                null
            }
        }

        /**
         * One mark for each part the application asked to cache. The ones of the system prompt and the conversation
         * go on in [AnthropicConversation], and a missing tool list is simply nothing to mark. A cut somewhere
         * precise is marked on the part instead, and travels in its metadata.
         *
         * The mark of the tools goes on the last one that is not deferred: Anthropic answers 400 to a deferred tool
         * with a mark, and leaves the deferred ones out of the prefix it caches anyway.
         */
        private fun applyCache(body: JsonObject, cache: AnthropicCache, conversation: AnthropicConversation) {
            if (cache.tools) {
                body["tools"]?.asArray()?.mapNotNull { it.asObject() }
                    ?.lastOrNull { it["defer_loading"]?.asBoolean() != true }
                    ?.set("cache_control", markOf(cache))
            }

            conversation.markCache(body)
        }

        private fun anthropicOptionsOf(options: ProviderOptions): AnthropicOptions? {
            options.providers.filter { it != ANTHROPIC_PROVIDER }.forEach {
                warnings.add(ModelWarning("Options for $it are not Anthropic options and were dropped"))
            }

            val ours = options.forProvider(ANTHROPIC_PROVIDER)

            ours.filter { it !is AnthropicOptions && it !is RawOptions }.forEach {
                warnings.add(ModelWarning("${it::class.simpleName} is not an option this adapter knows"))
            }

            // Several add up in order, as an agent's and then a run's do: what a later one sets wins
            return ours.filterIsInstance<AnthropicOptions>()
                .reduceOrNull { earlier, later -> earlier.overriddenBy(later) }
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
    }
}

/**
 * A request ready to go: its body, what was changed on the way, the beta headers it needs, and what the thinking of
 * its answer has to remember ([ThinkingStamp]).
 */
internal data class MappedRequest(
    val body: JsonObject,
    val warnings: List<ModelWarning>,
    val betas: Set<String> = emptySet(),
    val stamp: ThinkingStamp? = null,
    /** Whether a thinking block of the answer that carries text is a note between tool calls, not thinking. */
    val notes: Boolean = false,
)

/** Only reached by a model nobody described, where there is nothing to derive a ceiling from. */
internal const val FALLBACK_MAX_TOKENS = 4_096

internal val AnthropicEfforts.wireName get() = name.lowercase()

private val AnthropicServiceTiers.wireName
    get() = if (this == AnthropicServiceTiers.StandardOnly) "standard_only" else name.lowercase()
