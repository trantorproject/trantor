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
    val team = mutableListOf<Agent>()
    val hooks = mutableListOf<AgentHooks>()
    val inputGuardrails = mutableListOf<InputGuardrail>()
    val outputGuardrails = mutableListOf<OutputGuardrail>()
    val toolGuardrails = mutableListOf<ToolGuardrail>()

    /**
     * The agents the conversation can be handed over to, besides the one the run starts with. Every handoff an agent
     * of the team declares has to be to one of them.
     */
    fun team(vararg agents: Agent) = apply { team.addAll(agents) }

    /** How many calls to the model the run may take, whichever agent makes them. Past them, [MaxStepsExceededError]. */
    fun maxSteps(steps: Int) = apply { maxSteps = steps }

    /** What the instructions and the tools can read about who the run acts for. */
    fun context(run: RunContext) = apply { context = run }

    /**
     * Options of a provider for this run, like the key that keeps the cache of a conversation together. They go
     * after the ones of the agent, so where both set something the run wins.
     */
    fun options(vararg options: ProviderOption) = apply { providerOptions.addAll(options) }

    /** Called around every step of this run, whichever agent runs it, after the global hooks and the agent's. */
    fun hooks(vararg hooks: AgentHooks) = apply { this.hooks.addAll(hooks) }

    /** Checks the conversation of this run, after the global ones and those of the agent it starts with. */
    fun inputGuardrails(vararg guardrails: InputGuardrail) = apply { inputGuardrails.addAll(guardrails) }

    /** Checks the final answer of this run, after the global ones and those of the agent that answered. */
    fun outputGuardrails(vararg guardrails: OutputGuardrail) = apply { outputGuardrails.addAll(guardrails) }

    /** Checks every call of this run, whichever agent asks for it, after the global ones and the agent's. */
    fun toolGuardrails(vararg guardrails: ToolGuardrail) = apply { toolGuardrails.addAll(guardrails) }

    /** Timeout, cancellation and headers, which apply to every call of the run. */
    fun callOptions(options: CallOptions) = apply { callOptions = options }
}
