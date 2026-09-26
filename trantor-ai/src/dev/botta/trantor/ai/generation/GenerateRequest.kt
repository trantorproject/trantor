package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.history.ContextPolicy
import dev.botta.trantor.ai.history.Session
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.providers.ProviderOption
import dev.botta.trantor.ai.providers.ProviderOptions
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolChoice

/**
 * What to ask the model in a run, written in the order it is sent:
 *
 * ```kotlin
 * ai.generate {
 *     model("fast")
 *     system("Answer questions about the catalog. Use the tools.")
 *     messages(history)
 *     user(question)
 *     tools(searchProducts, weather)
 * }
 * ```
 *
 * Without a model it goes to the one the application called `default`.
 */
class GenerateRequest {
    /** A model reference or an alias. Null is the `default` alias. */
    var model: String? = null
        private set
    val messages = mutableListOf<Message>()
    val tools = mutableListOf<Tool<*>>()
    var toolChoice: ToolChoice = ToolChoice.Auto
        private set
    var maxSteps = ToolLoop.DEFAULT_MAX_STEPS
        private set
    var output: OutputSpec = OutputSpec.Text
        private set
    val settings = ChatSettings()
    val providerOptions = mutableListOf<ProviderOption>()
    var context = RunContext()
        private set
    var callOptions = CallOptions()
        private set
    var dynamicSystem: String? = null
        private set
    val contextPolicies = mutableListOf<ContextPolicy>()
    var session: Session? = null
        private set

    /** Where the messages that come after the session start, which the session keeps. */
    private var afterSession = 0

    fun model(reference: String?) = apply { model = reference }

    fun system(text: String) = apply { messages.add(Message.system(text)) }

    /** Instructions that change from one call to the next, sent where they do not undo the cache of the rest. */
    fun dynamicSystem(text: String) = apply { dynamicSystem = text }

    fun user(text: String) = apply { messages.add(Message.user(text)) }

    fun user(vararg parts: Part) = apply { messages.add(Message.User(parts.toList())) }

    /** The conversation so far, as the application kept it. */
    fun messages(history: List<Message>) = apply { messages.addAll(history) }

    /**
     * The conversation [session] keeps, read here and placed where this is called, since the request is written in
     * the order it is sent. Once the run ended well, the session keeps what comes after this in the request and what
     * the run added; what comes before, like the system prompt, is not kept. See [Session].
     */
    fun session(session: Session) = apply {
        check(this.session == null) { "A generation goes on from a single session" }

        this.session = session
        messages.addAll(session.load())
        afterSession = messages.size
    }

    /**
     * What part of the conversation each call sends, in the order given; the conversation itself stays whole. The
     * system messages it starts with always go. See [ContextPolicy].
     */
    fun contextPolicy(vararg policies: ContextPolicy) = apply { contextPolicies.addAll(policies) }

    fun tools(vararg tools: Tool<*>) = apply { this.tools.addAll(tools) }

    fun toolChoice(choice: ToolChoice) = apply { toolChoice = choice }

    /** How many calls to the model the run may take. Past them it fails with [MaxStepsExceededError]. */
    fun maxSteps(steps: Int) = apply { maxSteps = steps }

    fun output(spec: OutputSpec) = apply { output = spec }

    fun settings(configure: ChatSettings.() -> Unit) = apply { settings.configure() }

    /** Options of a provider. The ones for another provider are left out with a warning. */
    fun options(vararg options: ProviderOption) = apply { providerOptions.addAll(options) }

    /** What the tools can read about who they act for. */
    fun context(run: RunContext) = apply { context = run }

    /** Timeout, cancellation and headers, which apply to every call of the run. */
    fun callOptions(options: CallOptions) = apply { callOptions = options }

    /** Keeps in the session, if there is one, what came after it in the request and what [result] added. */
    internal fun keep(result: RunResult) {
        session?.append(messages.drop(afterSession) + result.newMessages)
    }

    /** The request of the first step. */
    fun toChatRequest() = ChatRequest(
        messages = messages.toList(),
        toolChoice = toolChoice,
        output = output,
        settings = settings.copy(),
        providerOptions = ProviderOptions.of(*providerOptions.toTypedArray()),
        dynamicSystem = dynamicSystem,
    )
}
