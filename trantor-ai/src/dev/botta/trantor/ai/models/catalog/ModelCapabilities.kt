package dev.botta.trantor.ai.models.catalog

import dev.botta.trantor.ai.models.chat.ReasoningEfforts

/**
 * What a model accepts, which is not the same across generations of the same family.
 *
 * Providers take parameters out between generations and the one left behind is not ignored: it is a 400. OpenAI
 * answers *"temperature does not support 0.2 with this model"* on its reasoning models; Anthropic stopped taking
 * `temperature` after Opus 4.6 and swapped a thinking budget for an effort level. This is what lets an adapter
 * drop a setting with a warning instead of sending it and losing the call.
 *
 * **A capability is the set or the range of values the model accepts**, and a plain yes/no is the case where that
 * set is empty or full. Inside a spec the answer is final: an empty [reasoningEfforts] means the model takes no
 * levels, not that nobody filled it in. A model that is not in the catalog has no spec at all, which is a
 * different thing — see [ModelCatalog].
 */
data class ModelCapabilities(
    /** The ceiling of an answer. Null means we don't know of one, not that there is none. */
    val maxOutputTokens: Int? = null,
    /** Null means the model refuses any temperature but its own. */
    val temperature: ValueRange? = null,
    val topP: ValueRange? = null,
    /** The levels the model takes. Empty means it takes none, so a level has to become something else. */
    val reasoningEfforts: Set<ReasoningEfforts> = emptySet(),
    /** The thinking budget in tokens, where the model takes one. Null means it does not. */
    val reasoningBudget: IntRange? = null,
    /** What is a plain yes or no. */
    val features: Set<ModelFeatures> = emptySet(),
) {
    operator fun contains(feature: ModelFeatures) = feature in features

    companion object {
        /** Everything a model of today takes, as a starting point for a family to narrow down with `copy`. */
        val Modern = ModelCapabilities(
            temperature = ValueRange.ZeroToTwo,
            topP = ValueRange.ZeroToOne,
            features = setOf(
                ModelFeatures.Tools,
                ModelFeatures.StructuredOutput,
                ModelFeatures.Images,
                ModelFeatures.ForcedToolUse,
            ),
        )
    }
}

/** What a model takes that is a plain yes or no, as the catalog writes it. */
enum class ModelFeatures {
    Tools,
    /**
     * An answer that follows a json schema. It is also what says whether a tool can be asked for strictly:
     * Anthropic compiles both through the same grammar, and documents one list of models for the two.
     */
    StructuredOutput,
    /**
     * The provider loads the tools of a call it was told to defer once the search of the application finds them:
     * Anthropic from the 4.5 generation on, and OpenAI from GPT-5.4 on. Without it, the tool loop tells the model
     * about the tools found like any other.
     */
    DeferredTools,
    Images,
    Audio,
    PromptCaching,
    /**
     * The model can be told to call a tool, either any of them or one by name. Claude Fable 5.1 and Mythos 5.1
     * answer 400 to both, and there the only thing to do is leave the choice to the model.
     */
    ForcedToolUse,
    /**
     * The model can be told not to reason at all. A model that always reasons answers 400 to being told that:
     * GPT-6 Astra and GPT-6.1 Sol refuse an effort of none, and Claude Opus 5.5, Sonnet 5.5, Fable and Mythos refuse
     * thinking disabled.
     *
     * On OpenAI it is also what gives the sampling settings back: the GPT-5.x families take a `temperature`
     * alongside an effort of none, and refuse it while they reason.
     */
    ReasoningOff,
    /**
     * The model cannot be told not to reason, but its lowest setting is not thinking before it answers, only between
     * tool calls, where it writes short progress notes. That is what being told not to reason becomes there. Claude
     * Sonnet 5.5, whose `between_tools` Anthropic takes only at an effort of high or below.
     */
    ThinkingBetweenTools,
    /**
     * Between tool calls the model can write a note for whoever watches the run, about what it found and what it will
     * do next, which comes back as a thinking block of its own: Claude Fable 5.1, Mythos 5.1, Opus 5.5, Sonnet 5.5
     * and Fable 5. It can be asked for apart from the thinking, so that it can be told from it.
     */
    ProgressNotes,
    /**
     * The model takes a system message anywhere in the conversation, not only at the start. It is what lets an
     * instruction that changes go after the conversation instead of before it, where changing it would undo the
     * cache of everything that follows. OpenAI takes them anywhere; among the Claude models only the newest do.
     */
    MidConversationSystem,
    /**
     * The model ties each thinking block to everything that came before it when it was produced — the system prompt,
     * the tools and every earlier message — and answers 400 when a later call sends the block back after any of that
     * changed. On such a model a conversation can only grow at its end. Claude Opus 5.5 and Fable 5.1 do it.
     */
    BoundThinking,
}

/** A closed range of a numeric setting, with the two that come up written down. */
data class ValueRange(val min: Double, val max: Double) {
    fun coerce(value: Double) = value.coerceIn(min, max)

    operator fun contains(value: Double) = value in min..max

    companion object {
        val ZeroToOne = ValueRange(0.0, 1.0)
        val ZeroToTwo = ValueRange(0.0, 2.0)
    }
}
