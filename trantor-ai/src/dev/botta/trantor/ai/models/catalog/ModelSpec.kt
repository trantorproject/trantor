package dev.botta.trantor.ai.models.catalog

import dev.botta.trantor.domain.Money

/**
 * A model the catalog knows, which is the only kind an adapter protects.
 *
 * [capabilities] is in the path of every call and [pricing] is not, which is why they are told apart: a price that
 * is out of date makes a report wrong, while a capability that is wrong breaks a call or drops a setting without a
 * word. The second is verified against a real call, like a fixture.
 */
data class ModelSpec(
    val provider: String,
    val modelId: String,
    val capabilities: ModelCapabilities,
    /** The list price, written on the same line as the capabilities. Nothing in the call path reads it. */
    val pricing: ModelPricing? = null,
    /**
     * True when nobody described this model and it is standing in for the newest one the catalog knows.
     * Whatever an adapter decides from a guess says so, because it can be wrong in a way a written entry is not.
     */
    val isGuess: Boolean = false,
) {
    val reference get() = "$provider/$modelId"
}

/**
 * The list price of a model, in dollars per million tokens, named as [dev.botta.trantor.ai.models.Usage] names the
 * tokens. [inputPerMillion] is the plain price, the one both providers call "input", and it is what the input that
 * went through no cache pays.
 *
 * A cache with no price of its own is charged at the plain input price, which is what OpenAI does with a write. One
 * price for writes, too, although Anthropic charges more for a cache kept an hour than for five minutes: this is
 * for an estimate, and the five-minute price is the one a call uses unless it asks otherwise.
 */
data class ModelPricing(
    val inputPerMillion: Money,
    val outputPerMillion: Money,
    val cacheReadPerMillion: Money? = null,
    val cacheWritePerMillion: Money? = null,
) {
    /** The price as the price list writes it, as text so it stays exactly that number. */
    constructor(input: String, output: String, cacheRead: String? = null, cacheWrite: String? = null): this(
        inputPerMillion = Money(input),
        outputPerMillion = Money(output),
        cacheReadPerMillion = cacheRead?.let { Money(it) },
        cacheWritePerMillion = cacheWrite?.let { Money(it) },
    )
}
