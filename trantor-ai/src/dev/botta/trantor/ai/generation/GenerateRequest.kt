package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.RunContext
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

    fun model(reference: String?) = apply { model = reference }

    fun system(text: String) = apply { messages.add(Message.system(text)) }

    fun user(text: String) = apply { messages.add(Message.user(text)) }

    fun user(vararg parts: Part) = apply { messages.add(Message.User(parts.toList())) }

    /** The conversation so far, as the application kept it. */
    fun messages(history: List<Message>) = apply { messages.addAll(history) }

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

    /** The request of the first step. */
    fun toChatRequest() = ChatRequest(
        messages = messages.toList(),
        toolChoice = toolChoice,
        output = output,
        settings = settings.copy(),
        providerOptions = ProviderOptions.of(*providerOptions.toTypedArray()),
    )
}
