package dev.botta.trantor.ai.agents

import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.TypedValues
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.ChatModel
import dev.botta.trantor.ai.models.chat.ChatSettings
import dev.botta.trantor.ai.providers.ProviderOption
import dev.botta.trantor.ai.tools.Tool
import kotlin.reflect.KClass

/**
 * A model with a role: its instructions, its tools, the agents it can hand the conversation over to and how it
 * answers. It is a value, built once and shared by every run:
 *
 * ```kotlin
 * val support = Agent("support")
 *     .model("default")
 *     .instructions(templates.render("support.md"))
 *     .dynamicInstructions { run -> "El contacto está asignado a ${run.require<ConversationState>().assignee}" }
 *     .tools(searchProducts, assignConversation)
 *     .handoffs("sales")
 *     .build()
 * ```
 *
 * The agents of an application are built by a factory of the application; the framework asks for no interface.
 */
class Agent internal constructor(
    /** Who it is within its team, and what the others hand over to. */
    val name: String,
    private val model: (ModelRegistry) -> ChatModel,
    private val instructions: ((RunContext) -> String)?,
    private val dynamicInstructions: ((RunContext) -> String)?,
    /** Its own tools, then the ones its handoffs turned into, then the one it answers with in [OutputMode.Tool]. */
    val tools: List<Tool<*>>,
    /** The tools it searches for instead of being told about them up front. */
    val searchableTools: List<Tool<*>>,
    /** The agents it can hand the conversation over to, by name. */
    val handoffs: List<String>,
    private val chatSettings: ChatSettings,
    /** Options of a provider for every call it makes. The ones for another provider are left out with a warning. */
    val options: List<ProviderOption>,
    /** The object it answers with, or null when it answers text. */
    val output: AgentOutput<*>?,
    private val values: TypedValues,
    /** Called around the steps it runs. See [AgentHooks]. */
    val hooks: List<AgentHooks>,
    /** Checks the conversation of a run that starts with it. See [InputGuardrail]. */
    val inputGuardrails: List<InputGuardrail>,
    /** Checks its final answer. See [OutputGuardrail]. */
    val outputGuardrails: List<OutputGuardrail>,
    /** Checks the calls of the steps it runs. See [ToolGuardrail]. */
    val toolGuardrails: List<ToolGuardrail>,
) {
    /** Its model: the one it was given, the reference it named, or the `default` alias. */
    fun modelFrom(models: ModelRegistry) = model(models)

    /**
     * The instructions that do not change within a conversation, which go first so that providers can cache them.
     * Asked on every step, since a function of the run can say something else each time.
     */
    fun instructions(run: RunContext) = instructions?.invoke(run)

    /**
     * The instructions that change from one call to the next — the time, the state of what the conversation is
     * about — which go last, where they do not undo the cache of the rest.
     */
    fun dynamicInstructions(run: RunContext) = dynamicInstructions?.invoke(run)

    /** A copy, so that whoever changes it changes only theirs. */
    val settings get() = chatSettings.copy()

    inline fun <reified T: Any> get(): T? = get(T::class)

    inline fun <reified T: Any> require(): T = require(T::class)

    /** The value that is a [type], its own class or an interface it implements; it fails when more than one is. */
    fun <T: Any> get(type: KClass<T>): T? = values.get(type)

    fun <T: Any> require(type: KClass<T>): T = values.require(type)

    /**
     * This agent as a tool of another: when the model calls it, the agent runs a run of its own with the task it was
     * given, and what it answers goes back as the result of the call; the conversation stays with the agent that
     * called it. It is the way to use a specialist without handing the conversation over to it.
     *
     * ```kotlin
     * val writer = Agent("writer")
     *     .tools(researcher.asTool(agents, "Finds the weather and the prices of a destination") { maxSteps(5) })
     *     .build()
     * ```
     *
     * See [AgentTool] for what the run of the agent gets and what it gives back.
     *
     * @param description what the agent is for, which is all the model knows to decide when to ask it.
     * @param maxDepth how many agents that run as tools this one can be inside of, counting itself: past it, the call
     * goes back to the model as an error, which is what ends a cycle of agents that use each other.
     * @param configure the options of the run of the agent, like its limit of steps.
     */
    fun asTool(
        runner: AgentRunner,
        description: String,
        name: String = this.name,
        maxDepth: Int = AgentTool.DEFAULT_MAX_DEPTH,
        configure: AgentRunOptions.() -> Unit = {},
    ) = AgentTool(this, runner, name, description, maxDepth, configure)

    override fun toString() = "Agent($name)"

    companion object {
        /** Starts building the agent called [name]. */
        operator fun invoke(name: String) = AgentBuilder(name)
    }
}
