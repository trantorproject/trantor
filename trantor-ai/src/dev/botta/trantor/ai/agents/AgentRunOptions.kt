package dev.botta.trantor.ai.agents

import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.generation.MaxStepsExceededError
import dev.botta.trantor.ai.generation.ToolLoop
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.providers.ProviderOption

/** What belongs to one run of the agents and not to the agents themselves. */
class AgentRunOptions {
    var maxSteps = ToolLoop.DEFAULT_MAX_STEPS
        private set
    var context = RunContext()
        private set
    val providerOptions = mutableListOf<ProviderOption>()
    var callOptions = CallOptions()
        private set

    /** How many calls to the model the run may take, whichever agent makes them. Past them, [MaxStepsExceededError]. */
    fun maxSteps(steps: Int) = apply { maxSteps = steps }

    /** What the instructions and the tools can read about who the run acts for. */
    fun context(run: RunContext) = apply { context = run }

    /**
     * Options of a provider for this run, like the key that keeps the cache of a conversation together. They go
     * after the ones of the agent, so where both set something the run wins.
     */
    fun options(vararg options: ProviderOption) = apply { providerOptions.addAll(options) }

    /** Timeout, cancellation and headers, which apply to every call of the run. */
    fun callOptions(options: CallOptions) = apply { callOptions = options }
}
