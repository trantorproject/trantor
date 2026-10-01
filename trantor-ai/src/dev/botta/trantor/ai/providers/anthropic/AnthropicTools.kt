package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.models.ModelWarning
import dev.botta.trantor.ai.models.chat.ChatRequest
import dev.botta.trantor.ai.schemas.StrictSchema
import dev.botta.trantor.ai.tools.*
import dev.botta.trantor.ai.tools.search.ClientToolSearch

/**
 * The tools of a request to Anthropic, and how free the model is to call them: strict while they fit in
 * [StrictBudget], deferred where the search of the application can find them, and the choice of calling one as the
 * model takes it. One for each request, since it keeps what it noticed on the way.
 */
internal class AnthropicTools(
    private val modelId: String,
    private val supports: ModelSupport,
    private val warnings: MappingWarnings,
    private val search: ClientToolSearch,
    private val strictBudget: StrictBudget,
) {
    /**
     * The tools and how free the model is to call them. Anthropic keeps the two together: whether calls can
     * run in parallel is a field of `tool_choice` and not of the request, so with no tools there is nowhere
     * to put it.
     */
    fun applyTo(body: JsonObject, request: ChatRequest, thinking: JsonObject?) {
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

        if (!supports.tools) {
            warnings.droppedByTheModel("tools")

            return
        }

        body["tools"] = Json.array(toTools(request.tools))
        body["tool_choice"] = toToolChoice(request.toolChoice, request.settings.parallelToolCalls, thinking)
    }

    /**
     * The tools as Anthropic takes them. With the search of the application in the request, on a model that loads
     * deferred tools, the deferred ones go after the rest and Anthropic loads the ones the search finds; the search
     * itself goes up front, and one tool at least has to. Anywhere else they go up front, with a warning: nothing
     * would find them.
     *
     * They go strict in that order while they fit in [StrictBudget], so that the ones the model has at hand are
     * the last to lose it; the rest go without, which Anthropic takes, and a warning names them.
     */
    private fun toTools(tools: List<ToolSpec>): List<JsonObject> {
        val (deferred, upFront) = tools.partition { it is FunctionToolSpec && it.deferLoading }
        if (deferred.isNotEmpty() && !defersTools) warnings.add(upFrontWarning(deferred))

        val sent = (if (defersTools) upFront + deferred else tools).mapNotNull { toTool(it) }
        warnNotStrict()

        return sent
    }

    /** Whether the deferred tools go deferred: the model loads them, and there is a search to find them. */
    private val defersTools get() = supports.deferredTools && search.spec != null

    private fun upFrontWarning(deferred: List<ToolSpec>): ModelWarning {
        val names = deferred.joinToString { it.name }
        val why =
            if (supports.deferredTools) "the request has no search to find them" else "$modelId does not load them"

        return ModelWarning("$names went up front: $why", "tools")
    }

    private val notStrict = mutableListOf<String>()

    private fun warnNotStrict() {
        if (notStrict.isEmpty()) return

        val message = "Anthropic holds a call to at most ${StrictBudget.MAX_TOOLS} strict tools and " +
            "${StrictBudget.MAX_UNIONS} parameters that can be null, so ${notStrict.joinToString()} " +
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
        val closed = if (strictly(tool)) closedSchema(tool.parameters) else null
        val strict = closed != null && strictBudget.take(closed)
        if (closed != null && !strict) notStrict.add(tool.name)

        val json = Json.obj("name" to tool.name, "input_schema" to if (strict) closed else tool.parameters)

        tool.description?.let { json["description"] = it }
        if (strict) json["strict"] = true
        if (tool.deferLoading && defersTools) json["defer_loading"] = true

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

        if (!supports.structuredOutput) {
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

        if (!supports.forcedToolUse) {
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
}

/**
 * A schema the model is held to has to be closed: every object saying `additionalProperties: false` and naming every
 * property it has. The adapter closes it instead of making everyone write it by hand.
 */
internal fun closedSchema(schema: JsonObject) = StrictSchema.of(schema, AnthropicStrictRules)
