package dev.botta.trantor.ai.agents

import dev.botta.trantor.ai.models.chat.Message
import dev.botta.trantor.ai.models.chat.ToolCallPart

/*
 * A guardrail is a check that can stop a run, which a hook cannot: a hook watches or changes what goes, and a
 * guardrail decides whether it goes at all. There are three, one for each thing a run can go wrong on: what the user
 * asked, what the agent answered and what the model asked a tool to do.
 *
 * They are declared on the agent, on the run and globally with `addGuardrails`, and asked in that order, one after
 * the other: the first that does not pass decides, and the ones after it are not asked. A check that calls a model,
 * like a classifier, costs a call each time it is asked.
 */

/**
 * Checks the conversation a run got before the first call to the model, so a request it should not take costs no
 * call and runs no tool. The ones asked are those of the agent the run starts with.
 *
 * ```kotlin
 * val offTopic = InputGuardrail("off-topic") { _, conversation ->
 *     if (classifier.isAboutTheWeather(conversation.last())) GuardrailVerdict.Pass
 *     else GuardrailVerdict.Trip("It is not about the weather")
 * }
 * ```
 *
 * They always run before the agent, so that it spends no tokens and runs no tool on a conversation the check stops.
 */
interface InputGuardrail {
    /** Which one it was, in the [GuardrailTrippedError]. The name of its class unless it says otherwise. */
    val name: String get() = nameOf(this)

    /** [conversation] is the one the run got, whose last message is usually what the user just said. */
    fun check(run: AgentHookContext, conversation: List<Message>): GuardrailVerdict

    companion object {
        operator fun invoke(name: String, check: (AgentHookContext, List<Message>) -> GuardrailVerdict) =
            object: InputGuardrail {
                override val name = name

                override fun check(run: AgentHookContext, conversation: List<Message>) = check(run, conversation)
            }
    }
}

/**
 * Checks the final answer of a run, once it ended well and before `afterRun`. The ones asked are those of the agent
 * that answered. It sees the whole [AgentRunResult], so an object answered with a tool is read with
 * [AgentRunResult.output].
 *
 * Only the final answer is checked: the text of an agent before it calls a tool, or of an agent that handed the
 * conversation over, is not.
 *
 * In a stream, the text of the answer is held back until the check passes, and then comes out all together, because
 * a check of safety that lets the text out before it runs is no check at all. A check of quality can say [holdsText]
 * false, and then the text comes out as it is written and the check runs at the end.
 */
interface OutputGuardrail {
    /** Which one it was, in the [GuardrailTrippedError]. The name of its class unless it says otherwise. */
    val name: String get() = nameOf(this)

    /** Whether a stream holds the text of the answer back until this check passed. */
    val holdsText: Boolean get() = true

    fun check(run: AgentHookContext, result: AgentRunResult): GuardrailVerdict

    companion object {
        operator fun invoke(
            name: String,
            holdsText: Boolean = true,
            check: (AgentHookContext, AgentRunResult) -> GuardrailVerdict,
        ) = object: OutputGuardrail {
            override val name = name
            override val holdsText = holdsText

            override fun check(run: AgentHookContext, result: AgentRunResult) = check(run, result)
        }
    }
}

/**
 * Checks each call the model asks for, with its args as the model sent them, before the hooks change them. The ones
 * asked are those of the agent whose step asked for the call, for every one of its tools, the handoffs and the
 * output tool included; a guardrail about one tool looks at [ToolCallPart.toolName].
 *
 * Every call of a step is checked before any of them runs, so a trip leaves no step half done: none of its tools had
 * any effect. A rejected call is answered to the model as an error and the others run. A call can also be left
 * waiting for a person to approve it, with the rules that depend on the moment, like who the user is or how much
 * it is: see [ToolGuardrailVerdict.AskForApproval].
 */
interface ToolGuardrail {
    /** Which one it was, in the [GuardrailTrippedError] and in the warning of a rejected call. */
    val name: String get() = nameOf(this)

    fun check(call: ToolCallPart, context: AgentToolContext): ToolGuardrailVerdict

    companion object {
        operator fun invoke(name: String, check: (ToolCallPart, AgentToolContext) -> ToolGuardrailVerdict) =
            object: ToolGuardrail {
                override val name = name

                override fun check(call: ToolCallPart, context: AgentToolContext) = check(call, context)
            }
    }
}

/** The guardrails every run of the [AgentRunner] asks, added with `addGuardrails`, before the agent's and the run's. */
class GlobalGuardrails {
    private val input = mutableListOf<InputGuardrail>()
    private val output = mutableListOf<OutputGuardrail>()
    private val tool = mutableListOf<ToolGuardrail>()

    val inputGuardrails: List<InputGuardrail>
        @Synchronized get() = input.toList()

    val outputGuardrails: List<OutputGuardrail>
        @Synchronized get() = output.toList()

    val toolGuardrails: List<ToolGuardrail>
        @Synchronized get() = tool.toList()

    @Synchronized
    fun input(vararg guardrails: InputGuardrail) = apply { input.addAll(guardrails) }

    @Synchronized
    fun output(vararg guardrails: OutputGuardrail) = apply { output.addAll(guardrails) }

    @Synchronized
    fun tool(vararg guardrails: ToolGuardrail) = apply { tool.addAll(guardrails) }
}

// An anonymous object has no simple name, so it goes by the name the JVM gave it
private fun nameOf(guardrail: Any) = guardrail::class.simpleName ?: guardrail.javaClass.name
