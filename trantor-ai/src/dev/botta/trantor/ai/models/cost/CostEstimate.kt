package dev.botta.trantor.ai.models.cost

import dev.botta.trantor.domain.Money

/**
 * What a call probably cost, in dollars, from the list price of its model and the tokens the provider reported.
 *
 * **It is an estimate and not the bill.** It knows the plain price of a model and nothing of what moves the bill
 * away from it: batch or priority tiers, the higher price of a long context, where the data is kept, discounts,
 * taxes. It is close enough to see where the money goes and to notice when it starts going faster, which is what it
 * is for. The bill is the provider's.
 *
 * It is split the way [dev.botta.trantor.ai.models.Usage] is, with the same names: [input] is [uncachedInput] plus
 * [cacheRead] plus [cacheWrite], and [reasoning] is part of [output]. The parts are the point, because they are what
 * says whether the cache is paying for itself or the model is thinking more than it answers.
 *
 * Nothing is rounded: a call costs fractions of a cent, and rounding belongs to whoever shows the number.
 */
data class CostEstimate(
    val uncachedInput: Money,
    val cacheRead: Money,
    val cacheWrite: Money,
    val output: Money,
    /** Part of [output], billed at its price. Zero when the provider did not say how much of it was thinking. */
    val reasoning: Money,
) {
    val input get() = uncachedInput + cacheRead + cacheWrite

    val total get() = input + output

    operator fun plus(other: CostEstimate) = CostEstimate(
        uncachedInput = uncachedInput + other.uncachedInput,
        cacheRead = cacheRead + other.cacheRead,
        cacheWrite = cacheWrite + other.cacheWrite,
        output = output + other.output,
        reasoning = reasoning + other.reasoning,
    )
}
