package dev.botta.trantor.ai.models.middleware

import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ResponseInfo
import dev.botta.trantor.ai.models.catalog.ModelCatalog
import dev.botta.trantor.ai.models.chat.ChatRequest
import dev.botta.trantor.ai.models.chat.ChatResponse
import dev.botta.trantor.ai.models.chat.ChatStream
import dev.botta.trantor.ai.models.cost.CostCalculator

/**
 * Puts what a call probably cost in [ResponseInfo.estimatedCost], so whoever wants the number finds it in the
 * response without asking for it. A model with no price in the catalog comes back without one; see
 * [CostCalculator] for when that is.
 *
 * It takes the catalog every provider shares, which is what lets the container build it:
 *
 * ```kotlin
 * services.addAI { models, services -> models.use(services.create<CostMiddleware>()) }
 * ```
 *
 * A stream is left as it comes: the parts go through untouched, and only its response carries the estimate, since
 * the tokens are only known once the provider says the answer is over.
 */
class CostMiddleware(catalog: ModelCatalog): ChatModelMiddleware {
    private val calculator = CostCalculator(catalog)

    override fun generate(
        request: ChatRequest,
        options: CallOptions,
        next: (ChatRequest, CallOptions) -> ChatResponse,
    ) = withEstimate(next(request, options))

    override fun stream(
        request: ChatRequest,
        options: CallOptions,
        next: (ChatRequest, CallOptions) -> ChatStream,
    ): ChatStream = EstimatedStream(next(request, options))

    private fun withEstimate(response: ChatResponse) =
        response.copy(info = response.info.copy(estimatedCost = calculator.estimate(response)))

    private inner class EstimatedStream(private val stream: ChatStream): ChatStream by stream {
        override fun response() = withEstimate(stream.response())
    }
}
