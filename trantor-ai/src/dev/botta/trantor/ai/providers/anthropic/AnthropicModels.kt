package dev.botta.trantor.ai.providers.anthropic

/**
 * What a Claude model takes, which is not the same for all of them.
 *
 * Anthropic moved things between generations: `temperature` stopped being accepted, a token budget for thinking
 * gave way to an effort level, and structured output only exists from a point onwards. Sending a setting to a model
 * that dropped it is a 400, so the adapter has to know which is which.
 *
 * This is a table and not a question asked to the api, because there is nothing to ask: the capabilities are in the
 * documentation, not in an endpoint. It is therefore going to go stale, which is why [isKnown] exists and why
 * nothing here ever refuses to call: an id the table does not recognize is assumed to be newer than everything in
 * it, and the call goes out.
 */
internal data class ModelCapabilities(
    /** The ceiling of `max_tokens`, which is also what a call that did not ask for one gets. */
    val maxOutputTokens: Int,
    /** `output_config.effort`, which arrived with adaptive thinking. */
    val takesEffort: Boolean,
    /** `thinking.type = enabled` with a budget in tokens. The models that take effort reject it. */
    val takesThinkingBudget: Boolean,
    /** `output_config.format`, the native json schema. */
    val takesStructuredOutput: Boolean,
    /** `temperature`, `top_p` and `top_k`, which models after Opus 4.6 reject with anything but their default. */
    val takesSamplingSettings: Boolean,
    /** False when the id did not match the table, so whoever cares can say that a default was a guess. */
    val isKnown: Boolean = true,
) {
    companion object {
        fun of(modelId: String) = known.firstOrNull { it.first(modelId) }?.second ?: guessFor(modelId)

        /**
         * A Claude id nobody recognized is newer than this table, so it gets what the newest generation takes.
         * Anything else is an Anthropic compatible api behind the same wire format, where the safe guess is the
         * oldest: sending a field such a server never heard of is a failed call, while not sending it is an
         * answer that is merely plainer.
         */
        private fun guessFor(modelId: String) =
            if (modelId.contains("claude")) newest.copy(isKnown = false) else oldest.copy(isKnown = false)

        private val newest = ModelCapabilities(
            maxOutputTokens = 128_000,
            takesEffort = true,
            takesThinkingBudget = false,
            takesStructuredOutput = true,
            takesSamplingSettings = false,
        )

        private val oldest = ModelCapabilities(
            maxOutputTokens = 4_096,
            takesEffort = false,
            takesThinkingBudget = false,
            takesStructuredOutput = false,
            takesSamplingSettings = true,
        )

        /**
         * Read in order, so a longer id wins over the family it belongs to: `claude-opus-4-8` has to be answered
         * before `claude-opus-4`.
         */
        private val known: List<Pair<(String) -> Boolean, ModelCapabilities>> = listOf(
            // Effort only: a thinking budget is a 400 here, and so is any sampling setting
            startsWithAnyOf("claude-opus-5", "claude-sonnet-5", "claude-opus-4-8", "claude-opus-4-7") to newest,
            startsWithAnyOf("claude-fable-5", "claude-mythos-5") to newest,
            // The generation in the middle: takes both, though the budget is already deprecated
            startsWithAnyOf("claude-sonnet-4-6", "claude-opus-4-6") to newest.copy(
                takesThinkingBudget = true,
                takesSamplingSettings = true,
            ),
            // The only one of its generation that takes effort, alongside the budget
            startsWithAnyOf("claude-opus-4-5") to ModelCapabilities(
                maxOutputTokens = 64_000,
                takesEffort = true,
                takesThinkingBudget = true,
                takesStructuredOutput = true,
                takesSamplingSettings = true,
            ),
            startsWithAnyOf("claude-sonnet-4-5", "claude-haiku-4-5") to ModelCapabilities(
                maxOutputTokens = 64_000,
                takesEffort = false,
                takesThinkingBudget = true,
                takesStructuredOutput = true,
                takesSamplingSettings = true,
            ),
            startsWithAnyOf("claude-opus-4-1") to ModelCapabilities(
                maxOutputTokens = 32_000,
                takesEffort = false,
                takesThinkingBudget = true,
                takesStructuredOutput = true,
                takesSamplingSettings = true,
            ),
            startsWithAnyOf("claude-sonnet-4") to ModelCapabilities(
                maxOutputTokens = 64_000,
                takesEffort = false,
                takesThinkingBudget = true,
                takesStructuredOutput = false,
                takesSamplingSettings = true,
            ),
            startsWithAnyOf("claude-opus-4") to ModelCapabilities(
                maxOutputTokens = 32_000,
                takesEffort = false,
                takesThinkingBudget = true,
                takesStructuredOutput = false,
                takesSamplingSettings = true,
            ),
            startsWithAnyOf("claude-3") to oldest,
        )

        private fun startsWithAnyOf(vararg prefixes: String): (String) -> Boolean =
            { id -> prefixes.any { id.startsWith(it) } }
    }
}
