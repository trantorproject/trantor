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
import dev.botta.trantor.ai.tools.*
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
        Mapping(catalog.find(ANTHROPIC_PROVIDER, modelId), modelId).map(modelId, request, stream)

    /** [spec] is null when the provider has no latest set either, and then nothing here holds anything back. */
    private inner class Mapping(private val spec: ModelSpec?, private val modelId: String) {
        private val model: ModelCapabilities? = spec?.capabilities
        private val takes = WhatTheModelTakes(spec)
        private val warnings = MappingWarnings(modelId, takes.isGuess)

        /** Whether the thinking blocks of the answer that carry text are notes between tool calls (see [withNotes]). */
        private var notes = false
        private val betas = mutableSetOf<String>()
        private val strictBudget = StrictBudget()

        fun map(modelId: String, request: ChatRequest, stream: Boolean = false): MappedRequest {
            val options = anthropicOptionsOf(request.providerOptions)
            val cache = options?.cache ?: config.cache
            val thinking = toThinking(request.settings.reasoning, options)
            val conversation = AnthropicConversation(takes, warnings, cache, modelId, ClientToolSearch(request.tools))
            val body = Json.obj("model" to modelId)

            conversation.writeTo(body, request.messages, request.dynamicSystem)
            // Required by the api, unlike everywhere else, so there is always a number to send
            body["max_tokens"] = maxTokensFor(request.settings, thinking)
            thinking?.let { body["thinking"] = it }

            if (stream) body["stream"] = true

            applySettings(body, request.settings)
            // The answer in JSON before the tools: it takes its share of what can go strict, and a tool can do without
            applyOutputConfig(body, request.output, request.settings.reasoning, options)
            applyTools(body, request, thinking)
            conversation.bindThinking(body)
            applyCache(body, cache, conversation)

            options?.userId?.let { body["metadata"] = Json.obj("user_id" to it) }
            options?.serviceTier?.let { body["service_tier"] = it.wireName }

            applyRawOptions(body, request.providerOptions)

            if (request.settings.failOnWarnings && warnings.isNotEmpty()) {
                throw UnsupportedRequestError(ANTHROPIC_PROVIDER, warnings.toList())
            }

            return MappedRequest(body, warnings.toList(), conversation.betas + betas, conversation.stamp, notes)
        }

        private fun applySettings(body: JsonObject, settings: ChatSettings): Unit = with(settings) {
            stopSequences?.let { body["stop_sequences"] = Json.array(it.map { stop -> Json.value(stop) }) }
            seed?.let { warnings.unsupportedSetting("seed") }

            if (temperature == null && topP == null) return

            // Deprecated from Claude Opus 4.6 on: the models after it answer 400 to anything but the default
            if (!takes.samplingSettings) {
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
         * The tools and how free the model is to call them. Anthropic keeps the two together: whether calls can
         * run in parallel is a field of `tool_choice` and not of the request, so with no tools there is nowhere
         * to put it.
         */
        private fun applyTools(body: JsonObject, request: ChatRequest, thinking: JsonObject?) {
            if (request.tools.isEmpty()) {
                request.settings.parallelToolCalls?.let {
                    warnings.add(
                        ModelWarning(
                            "Anthropic takes parallelToolCalls inside tool_choice, and the call has no tools",
                            "parallelToolCalls",
                        )
                    )
                }

                return
            }

            if (!takes.tools) {
                warnings.droppedByTheModel("tools")

                return
            }

            body["tools"] = Json.array(toTools(request.tools))
            body["tool_choice"] = toToolChoice(request.toolChoice, request.settings.parallelToolCalls, thinking)
        }

        /**
         * The tools as Anthropic takes them: the deferred ones after the rest, and its tool search last, unless the
         * application searches on its own. One tool at least has to be told up front, which the search always is. A
         * model that does not search gets them all up front, with a warning.
         *
         * They go strict in that order while they fit in [StrictBudget], so that the ones the model has at hand are
         * the last to lose it; the rest go without, which Anthropic takes, and a warning names them.
         */
        private fun toTools(tools: List<ToolSpec>): List<JsonObject> {
            val (deferred, upFront) = tools.partition { it is FunctionToolSpec && it.deferLoading }
            val searching = deferred.isNotEmpty() && takes.toolSearch
            // A search of the application is the search, and Anthropic's own would be a second one
            val ownSearch = ClientToolSearch(tools).spec == null

            if (deferred.isNotEmpty() && !searching) {
                val names = deferred.joinToString { it.name }
                warnings.add(ModelWarning("$modelId does not search tools, so $names went up front", "tools"))
            }

            val sent = (if (searching) upFront + deferred else tools).mapNotNull { toTool(it) }
            warnBeyondStrictBudget()

            return if (searching && ownSearch) sent + Json.obj("type" to TOOL_SEARCH_TYPE, "name" to TOOL_SEARCH_NAME)
            else sent
        }

        private val beyondStrictBudget = mutableListOf<String>()

        private fun warnBeyondStrictBudget() {
            if (beyondStrictBudget.isEmpty()) return

            val message = "Anthropic holds a call to at most ${StrictBudget.MAX_TOOLS} strict tools and " +
                "${StrictBudget.MAX_UNIONS} parameters that can be null, so ${beyondStrictBudget.joinToString()} " +
                "went without strict mode"
            warnings.add(ModelWarning(message, "tools"))
        }

        private fun toTool(tool: ToolSpec): JsonObject? = when (tool) {
            is FunctionToolSpec -> toFunctionTool(tool)
            // A tool of the provider is named by the versioned type Anthropic gave it, and its own name is an arg
            is ProviderToolSpec -> if (tool.name.startsWith("$ANTHROPIC_PROVIDER.")) {
                Json.obj().apply { merge(tool.args) }.with("type", tool.name.removePrefix("$ANTHROPIC_PROVIDER."))
            } else {
                warnings.add(ModelWarning("Tool ${tool.name} is not an Anthropic tool and was dropped"))
                null
            }
        }

        private fun toFunctionTool(tool: FunctionToolSpec): JsonObject {
            val closed = if (strictly(tool)) schemaFor(tool.parameters, strict = true) else null
            val strict = closed != null && strictBudget.take(closed)
            if (closed != null && !strict) beyondStrictBudget.add(tool.name)

            val json = Json.obj("name" to tool.name, "input_schema" to if (strict) closed else tool.parameters)

            tool.description?.let { json["description"] = it }
            if (strict) json["strict"] = true
            if (tool.deferLoading && takes.toolSearch) json["defer_loading"] = true

            return json
        }

        /**
         * Holding the model to the schema is the same grammar as structured output, and the same models have it.
         * Where it is not there the tool still goes, because a tool nobody can be held to is far better than no
         * tool at all — what is lost is the guarantee, and that is what the warning says. The same goes for a schema
         * that refers to itself, which Anthropic cannot hold a model to.
         */
        private fun strictly(tool: FunctionToolSpec): Boolean {
            if (!tool.strict) return false

            if (!takes.structuredOutput) {
                warnings.add(
                    ModelWarning(
                        "$modelId cannot be held to the schema of ${tool.name}, so the tool was sent without it" +
                            warnings.becauseItIsAGuess,
                        "tools",
                    )
                )
                return false
            }

            if (StrictSchema.refersToItself(tool.parameters)) {
                val message = "The schema of ${tool.name} refers to itself, which Anthropic cannot hold a model to, " +
                    "so the tool was sent without it"
                warnings.add(ModelWarning(message, "tools"))
                return false
            }

            return true
        }

        private fun toToolChoice(choice: ToolChoice, parallel: Boolean?, thinking: JsonObject?): JsonObject {
            val json = when (val asked = asTheModelTakesIt(choice, thinking)) {
                is ToolChoice.Auto -> Json.obj("type" to "auto")
                is ToolChoice.None -> Json.obj("type" to "none")
                is ToolChoice.Required -> Json.obj("type" to "any")
                is ToolChoice.Named -> Json.obj("type" to "tool", "name" to asked.name)
            }

            if (parallel == false) json["disable_parallel_tool_use"] = true

            return json
        }

        /**
         * Being told to call a tool is refused in two different ways, so both are asked before it is sent. Fable
         * 5.1 and Mythos 5.1 answer 400 to it at all; and any model refuses it while it thinks to a budget, which
         * is the second capability that is not a property of the model but of two settings meeting.
         *
         * What is left either way is auto, which is the default: the model is free to call the tool instead of
         * having to, and the call goes out.
         */
        private fun asTheModelTakesIt(choice: ToolChoice, thinking: JsonObject?): ToolChoice {
            if (choice != ToolChoice.Required && choice !is ToolChoice.Named) return choice

            if (!takes.forcedToolUse) {
                warnings.droppedByTheModel("toolChoice")

                return ToolChoice.Auto
            }

            if (thinking?.get("type")?.asString() != BUDGET_THINKING) return choice

            warnings.add(
                ModelWarning(
                    "$modelId does not take a forced tool call while it thinks to a budget, " +
                        "so the model was left to choose",
                    "toolChoice",
                )
            )

            return ToolChoice.Auto
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

            return withNotes(thinkingFor(reasoning, options))
        }

        /**
         * On a model that writes notes between tool calls ([WhatTheModelTakes.progressNotes]), thinking that shows
         * nothing of itself — none asked for, or no summary — asks for the notes apart, with `display: "updates"`:
         * they come back with their text and the thinking blocks stay empty, so the two can be told apart
         * (build-with-claude/thinking, "Progress updates between tool calls", read on 2026-09-30). With no thinking
         * field these models think adaptively already, so asking for it changes nothing of how much they think.
         * `between_tools` brings the notes with their text on its own. A summary is left as it is: its notes cannot
         * be told from the thinking.
         */
        private fun withNotes(thinking: JsonObject?): JsonObject? {
            if (!takes.progressNotes) return thinking

            val type = thinking?.get("type")?.asString()
            if (type == BETWEEN_TOOLS_THINKING) notes = true
            if (thinking != null && (type != ADAPTIVE_THINKING || thinking["display"]?.asString() != OMITTED)) {
                return thinking
            }

            notes = true
            betas.add(NOTES_BETA)

            return Json.obj("type" to ADAPTIVE_THINKING, "display" to NOTES_DISPLAY)
        }

        private fun thinkingFor(reasoning: Reasoning?, options: AnthropicOptions?): JsonObject? {
            if (reasoning == null) return null
            if (reasoning == Reasoning.Off) return thinkingOff(options?.effort)

            reasoning.budgetTokens?.let {
                if (takes.budget) return budgetThinking(it, reasoning.summary)

                warnings.droppedByTheModel("reasoning.budgetTokens")
                return null
            }

            if (reasoning.effort == null) return null
            if (takes.effort) return adaptiveThinking(reasoning.summary)
            if (takes.budget) return budgetThinking(budgetFor(reasoning.effort), reasoning.summary)

            warnings.droppedByTheModel("reasoning")

            return null
        }

        private fun toThinking(thinking: AnthropicThinking) = when (thinking) {
            is AnthropicThinking.Off -> Json.obj("type" to "disabled")
            is AnthropicThinking.Adaptive -> adaptiveThinking(thinking.summary)
            is AnthropicThinking.Budget -> budgetThinking(thinking.tokens, thinking.summary)
            is AnthropicThinking.BetweenTools -> Json.obj("type" to BETWEEN_TOOLS_THINKING)
        }

        /**
         * A model that always thinks answers 400 to thinking disabled: there, thinking less is a lower effort. Sonnet
         * 5.5 has a lowest setting instead, `between_tools`, which does not think before answering and only writes
         * short notes between tool calls; it takes no other field, and Anthropic refuses it above an effort of high
         * (build-with-claude/thinking, read on 2026-09-30).
         */
        private fun thinkingOff(effort: AnthropicEfforts?): JsonObject? {
            if (takes.reasoningOff) return Json.obj("type" to "disabled")

            if (takes.thinkingBetweenTools) {
                if (effort != AnthropicEfforts.XHigh && effort != AnthropicEfforts.Max) {
                    return Json.obj("type" to BETWEEN_TOOLS_THINKING)
                }

                val message = "$modelId takes between_tools, its lowest thinking, only at an effort of high or " +
                    "below, so Reasoning.Off was not sent with an effort of ${effort.wireName}"
                warnings.add(ModelWarning(message, "reasoning"))
                return null
            }

            warnings.add(
                ModelWarning(
                    "$modelId always thinks, so Reasoning.Off was not sent; a lower effort is how it thinks less" +
                        warnings.becauseItIsAGuess,
                    "reasoning",
                )
            )

            return null
        }

        private fun adaptiveThinking(summary: ReasoningSummaries) =
            adaptiveThinking(summary != ReasoningSummaries.None)

        private fun adaptiveThinking(summary: Boolean) =
            Json.obj("type" to ADAPTIVE_THINKING, "display" to displayFor(summary))

        private fun budgetThinking(tokens: Int, summary: ReasoningSummaries) =
            budgetThinking(tokens, summary != ReasoningSummaries.None)

        private fun budgetThinking(tokens: Int, summary: Boolean): JsonObject {
            val range = model?.reasoningBudget

            return Json.obj(
                "type" to BUDGET_THINKING,
                // The api refuses anything under a thousand, and a budget over the ceiling leaves no room to answer
                "budget_tokens" to if (range == null) tokens else tokens.coerceIn(range.first, range.last),
            ).also { if (!summary) it["display"] = displayFor(false) }
        }

        /** Omitted still returns the signature, which is what carries the thinking to the next turn. */
        private fun displayFor(summary: Boolean) = if (summary) "summarized" else OMITTED

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

            val asked = reasoning?.effort?.takeIf { takes.effort } ?: return null
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
            is OutputSpec.Json -> if (takes.structuredOutput) {
                // Its answers in JSON are always held to their schema: there is nothing to fall back to
                if (StrictSchema.refersToItself(output.schema)) {
                    val message = "The schema of the answer refers to itself, which Anthropic cannot hold a model " +
                        "to, and it has no answer in JSON that is not held to its schema"
                    val warning = ModelWarning(message, "output")
                    throw UnsupportedRequestError(ANTHROPIC_PROVIDER, listOf(warning))
                }

                // Anthropic names no schema and takes no strict flag: closing the schema is the whole of it
                val schema = schemaFor(output.schema, strict = true).also { strictBudget.spend(it) }
                Json.obj("type" to "json_schema", "schema" to schema)
            } else {
                warnings.droppedByTheModel("output")
                null
            }
        }

        /**
         * A schema the model is held to has to be closed: every object saying `additionalProperties: false` and
         * naming every property it has. The adapter closes it instead of making everyone write it by hand.
         */
        private fun schemaFor(schema: JsonObject, strict: Boolean) =
            if (strict) StrictSchema.of(schema, AnthropicStrictRules) else schema

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
            return ours.filterIsInstance<AnthropicOptions>().reduceOrNull { earlier, later -> earlier.overriddenBy(later) }
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

private const val BETWEEN_TOOLS_THINKING = "between_tools"
private const val ADAPTIVE_THINKING = "adaptive"
private const val OMITTED = "omitted"
private const val NOTES_DISPLAY = "updates"
private const val NOTES_BETA = "thinking-display-updates-2026-08-18"

/** The tool search of Anthropic by natural language, which the model writes more readily than a pattern. */
private const val TOOL_SEARCH_TYPE = "tool_search_tool_bm25_20251119"
private const val TOOL_SEARCH_NAME = "tool_search_tool_bm25"

/** Only reached by a model nobody described, where there is nothing to derive a ceiling from. */
private const val FALLBACK_MAX_TOKENS = 4_096

/** Thinking to a number of tokens, which is the shape that refuses a forced tool call. */
private const val BUDGET_THINKING = "enabled" 

private val AnthropicEfforts.wireName get() = name.lowercase()

private val ServiceTiers.wireName
    get() = if (this == ServiceTiers.StandardOnly) "standard_only" else name.lowercase()
