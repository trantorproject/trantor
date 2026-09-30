package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.history.Compaction
import dev.botta.trantor.ai.history.Compactor
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
    val searchableTools = mutableListOf<Tool<*>>()
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
    var compaction: Compaction? = null
        private set
    val decisions = mutableListOf<Decision>()

    /** Where the conversation of the session starts in the request, and where what comes after it does. */
    private var sessionStart = 0
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
        sessionStart = messages.size
        messages.addAll(session.load())
        afterSession = messages.size
    }

    /**
     * What part of the conversation each call sends, in the order given; the conversation itself stays whole. The
     * system messages it starts with always go. See [ContextPolicy].
     */
    fun contextPolicy(vararg policies: ContextPolicy) = apply { contextPolicies.addAll(policies) }

    fun tools(vararg tools: Tool<*>) = apply { this.tools.addAll(tools) }

    /**
     * Tools the model searches for instead of being told about them up front, for a catalog too large to send whole
     * on every call. See [Tool search](https://github.com/nbottarini/trantor/blob/main/docs/trantor-ai.md#tool-search).
     */
    fun searchableTools(vararg tools: Tool<*>) = apply { searchableTools.addAll(tools) }

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

    /**
     * Compacts the conversation the generation keeps once it ended well, if its last call went past [afterTokens]:
     * what the session had and what came after it, or the whole request without one. See [Compaction].
     */
    fun compaction(compactor: Compactor, afterTokens: Int) = apply { compaction = Compaction(compactor, afterTokens) }

    /**
     * What a person decided about the calls the conversation left waiting for approval, when a run paused on them.
     * The run answers them before it calls the model: the approved ones run, the others are answered as not approved,
     * and so is any call that got no decision. A decision about a call that is not waiting fails the run with
     * [dev.botta.trantor.ai.errors.NoPendingCallError] before anything happens. See [Decision].
     */
    fun decisions(vararg decisions: Decision) = apply { this.decisions.addAll(decisions) }

    /** [result] with the conversation compacted, if the request asked for it and it went past its tokens. */
    internal fun compacted(result: RunResult) = compaction?.after(
        result,
        result.keptAfter(messages.toList(), from = afterSession).drop(sessionStart),
        callOptions,
    ) ?: result

    /**
     * Keeps in the session, if there is one, what came after it in the request and what [result] added, or the
     * conversation compacted in place of all it had. The results of the calls the run resolved go first, right after
     * the answer the session ends with.
     */
    internal fun keep(result: RunResult) {
        val session = session ?: return
        val compacted = result.compacted

        if (compacted != null) session.replace(compacted.conversation)
        else session.append(result.keptAfter(messages.toList(), from = afterSession).drop(afterSession))
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
