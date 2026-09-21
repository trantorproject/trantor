package dev.botta.trantor.ai.models

import dev.botta.trantor.ai.models.cost.CostEstimate
import kotlin.time.Duration

data class ResponseInfo(
    // Id given by the provider, to look the call up on their side
    val id: String? = null,
    // Model that actually answered, which may differ from the one asked for
    val model: String,
    val provider: String,
    val latency: Duration,
    // Filled from the catalog by CostMiddleware. Null when there is no catalog or no price for the model.
    val estimatedCost: CostEstimate? = null,
)
