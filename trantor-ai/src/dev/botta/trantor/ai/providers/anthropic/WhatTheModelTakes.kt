package dev.botta.trantor.ai.providers.anthropic

import dev.botta.trantor.ai.models.catalog.ModelFeatures
import dev.botta.trantor.ai.models.catalog.ModelSpec

/**
 * What the model of a call takes, as the catalog says it, asked in one place instead of in every part of a mapping.
 *
 * With no spec at all, which is a catalog with no latest model set, nothing is held back: there is nothing to hold
 * it back with. A spec that is a guess, a model nobody described standing in for the newest one, says so in
 * [isGuess], so that every decision taken out of it can say so too.
 */
internal class WhatTheModelTakes(private val spec: ModelSpec?) {
    private val model = spec?.capabilities

    val isGuess get() = spec?.isGuess == true

    val samplingSettings get() = model == null || model.temperature != null
    val effort get() = model == null || model.reasoningEfforts.isNotEmpty()
    val budget get() = model == null || model.reasoningBudget != null
    val structuredOutput get() = has(ModelFeatures.StructuredOutput)
    val tools get() = has(ModelFeatures.Tools)
    val forcedToolUse get() = has(ModelFeatures.ForcedToolUse)
    val midConversationSystem get() = has(ModelFeatures.MidConversationSystem)
    val reasoningOff get() = has(ModelFeatures.ReasoningOff)
    val thinkingBetweenTools get() = has(ModelFeatures.ThinkingBetweenTools)

    /** Not taken for granted without a spec: the notes are asked for with a beta a model without them may refuse. */
    val progressNotes get() = model != null && ModelFeatures.ProgressNotes in model

    /** Not taken for granted without a spec: a model that does not search answers 400, and the loop can search. */
    val toolSearch get() = model != null && ModelFeatures.ToolSearch in model

    /** Not something the model takes but something it demands, so a model with no spec is taken not to. */
    val boundThinking get() = model != null && ModelFeatures.BoundThinking in model

    private fun has(feature: ModelFeatures) = model == null || feature in model
}
