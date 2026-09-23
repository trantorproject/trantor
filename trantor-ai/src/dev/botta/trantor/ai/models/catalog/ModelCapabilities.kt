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

enum class ModelFeatures {
    Tools,
    /**
     * An answer that follows a json schema. It is also what says whether a tool can be asked for strictly:
     * Anthropic compiles both through the same grammar, and documents one list of models for the two.
     */
    StructuredOutput,
    Images,
    Audio,
    PromptCaching,
    /**
     * The model can be told to call a tool, either any of them or one by name. Claude Fable 5.1 and Mythos 5.1
     * answer 400 to both, and there the only thing to do is leave the choice to the model.
     */
    ForcedToolUse,
    /**
     * The model can be told not to reason at all, and only then does it take the sampling settings again.
     * It is what tells a model that reasons by default from one that reasons because it was asked to: the GPT-5.x
     * families take a `temperature` alongside an effort of none, and GPT-6 refuses both.
     */
    ReasoningOff,
    /**
     * The model takes a system message anywhere in the conversation, not only at the start. It is what lets an
     * instruction that changes go after the conversation instead of before it, where changing it would undo the
     * cache of everything that follows. OpenAI takes them anywhere; among the Claude models only the newest do.
     */
    MidConversationSystem,
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
