package dev.botta.trantor.ai.models.cost

import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.ai.models.catalog.ModelPricing
import dev.botta.trantor.ai.models.chat.ChatResponse
import dev.botta.trantor.domain.Money

/**
 * Turns the tokens of a call into a [CostEstimate], with the prices of the catalog.
 *
 * It answers null rather than a number it cannot stand behind:
 *
 * - **A model with no price.** The price is all it asks the catalog for, so a model priced and never described is
 *   estimated all the same. What it never does is borrow a price: a model standing in for the newest one does not
 *   take the newest one's, because guessing what a model takes keeps a call from failing, while guessing what it
 *   costs gives a wrong number that looks like a right one. Being off by a tier is an estimate; being off by a
 *   model is not. See [ModelCatalog] for what a price follows.
 * - **A usage whose input or output is unknown.** Half a bill passes for the whole of it.
 */
class CostCalculator(private val catalog: ModelCatalog) {
    /** The estimate of a response, priced as the model that answered it and not the one that was asked for. */
    fun estimate(response: ChatResponse) = estimate(response.usage, response.info.provider, response.info.model)

    fun estimate(usage: Usage, provider: String, modelId: String): CostEstimate? {
        val pricing = catalog.priceOf(provider, modelId) ?: return null
        val uncachedInput = usage.uncachedInputTokens ?: return null
        val output = usage.outputTokens ?: return null

        return CostEstimate(
            uncachedInput = price(uncachedInput, pricing.inputPerMillion),
            cacheRead = price(usage.cacheReadTokens, pricing.cacheReadPerMillion ?: pricing.inputPerMillion),
            cacheWrite = price(usage.cacheWriteTokens, pricing.cacheWritePerMillion ?: pricing.inputPerMillion),
            output = price(output, pricing.outputPerMillion),
            reasoning = price(usage.reasoningTokens, pricing.outputPerMillion),
        )
    }

    /**
     * Multiplied before it is divided, so the division is by a power of ten and exact. Dividing the price first
     * would be exact too, but only by luck of the divisor.
     */
    private fun price(tokens: Int?, perMillion: Money) = perMillion * (tokens ?: 0) / ONE_MILLION

    private companion object {
        const val ONE_MILLION = 1_000_000
    }
}
