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
    val displayName: String? = null,
    val contextWindow: Int? = null,
    /** Filled by whoever wants costs. Nothing in the call path reads it. */
    val pricing: ModelPricing? = null,
    /**
     * True when nobody described this model and it is standing in for the newest one the catalog knows.
     * Whatever an adapter decides from a guess says so, because it can be wrong in a way a written entry is not.
     */
    val isGuess: Boolean = false,
) {
    val reference get() = "$provider/$modelId"
}

data class ModelPricing(
    val inputPerMillion: Money,
    val outputPerMillion: Money,
    val cachedInputPerMillion: Money? = null,
    val cacheWritePerMillion: Money? = null,
)
