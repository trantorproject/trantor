package dev.botta.trantor.ai.generation

import dev.botta.trantor.ai.errors.AIError

/**
 * The model was still asking for tools when the generation ran out of steps, so it never answered.
 *
 * It fails instead of returning because whoever asked for a generation expects an answer, and an empty one would
 * pass for it without anyone noticing. [steps] has what was done, usage included, so nothing spent is lost.
 */
class MaxStepsExceededError(val maxSteps: Int, val steps: List<Step>): AIError(
    "The model was still asking for tools after $maxSteps steps. " +
        "Raise maxSteps if the task needs more, or look at the steps for a tool that keeps failing",
)
