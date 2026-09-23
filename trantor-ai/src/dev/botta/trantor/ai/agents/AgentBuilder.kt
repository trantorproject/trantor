package dev.botta.trantor.ai.agents

import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.TypedValues
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.ChatModel
import dev.botta.trantor.ai.models.chat.ChatSettings
import dev.botta.trantor.ai.providers.ProviderOption
import dev.botta.trantor.ai.tools.Tool
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Builds an [Agent]. What it gets after [build] does not reach the agent already built. */
class AgentBuilder internal constructor(private val name: String) {
    private var model: (ModelRegistry) -> ChatModel = { it.chat() }
    private var instructions: ((RunContext) -> String)? = null
    private var dynamicInstructions: ((RunContext) -> String)? = null
    private val tools = mutableListOf<Tool<*>>()
    private val handoffs = mutableListOf<HandoffTool>()
    private val settings = ChatSettings()
    private val options = mutableListOf<ProviderOption>()
    private var output: AgentOutput<*>? = null
    private val values = mutableListOf<Any>()

    /** A model reference or an alias of the registry. Without one it is the `default` alias. */
    fun model(reference: String) = apply { model = { it.chat(reference) } }

    fun model(model: ChatModel) = apply { this.model = { model } }

    fun instructions(text: String) = apply { instructions = { text } }

    /** Instructions worked out on every step from the run, rendering a template if it takes one. */
    fun instructions(instructions: (RunContext) -> String) = apply { this.instructions = instructions }

    fun dynamicInstructions(text: String) = apply { dynamicInstructions = { text } }

    fun dynamicInstructions(instructions: (RunContext) -> String) = apply { dynamicInstructions = instructions }

    fun tools(vararg tools: Tool<*>) = apply { this.tools.addAll(tools) }

    /** Agents of the team it can hand the conversation over to, each with a tool `transfer_to_<name>`. */
    fun handoffs(vararg agents: String) = apply { agents.forEach { handoff(it) } }

    /** A handoff whose tool tells the model when to use it. */
    fun handoff(agent: String, description: String = HandoffTool.describe(agent)) =
        apply { handoffs.add(HandoffTool(agent, description)) }

    fun settings(configure: ChatSettings.() -> Unit) = apply { settings.configure() }

    fun options(vararg options: ProviderOption) = apply { this.options.addAll(options) }

    /** Answers with a [T] instead of text. */
    inline fun <reified T> output(mode: OutputMode = OutputMode.Native) = output(serializer<T>(), mode)

    fun <T> output(serializer: KSerializer<T>, mode: OutputMode = OutputMode.Native) =
        apply { output = AgentOutput(serializer, mode) }

    /** A value of the application the agent carries, found later by its type with [Agent.get]. */
    fun with(value: Any) = apply { values.add(value) }

    fun build(): Agent {
        val holder = "The agent $name"
        val tools = tools + handoffs
        val values = TypedValues(holder, values.toList())

        require(name.isNotEmpty()) { "An agent needs a name" }
        requireNameForATool(name)
        handoffs.forEach { requireNameForATool(it.agent) }
        require(handoffs.none { it.agent == name }) { "$holder hands over to itself" }
        tools.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }?.let {
            throw IllegalArgumentException("$holder has more than one tool called ${it.key}")
        }
        values.repeatedClass()?.let {
            throw IllegalArgumentException("$holder has two values of the class ${it.simpleName}")
        }

        return Agent(
            name = name,
            model = model,
            instructions = instructions,
            dynamicInstructions = dynamicInstructions,
            tools = tools,
            handoffs = handoffs.map { it.agent },
            chatSettings = settings.copy(),
            options = options.toList(),
            output = output,
            values = values,
        )
    }

    /**
     * Another agent hands over to this one with a tool called `transfer_to_<name>`, and a tool name takes letters,
     * digits, `_` and `-`: up to 64 of them in OpenAI and 128 in Anthropic. The name is checked rather than
     * rewritten, so the tool is called what the application wrote.
     */
    private fun requireNameForATool(agent: String) = require(NAME.matches(agent)) {
        "The name of an agent goes in the name of the tool that hands over to it, so it takes letters, digits, " +
            "_ and -, up to $MAX_NAME of them: $agent"
    }

    private companion object {
        val MAX_NAME = 64 - HandoffTool.toolName("").length
        val NAME = Regex("[A-Za-z0-9_-]{1,$MAX_NAME}")
    }
}
