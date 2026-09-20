package dev.botta.trantor.ai.models

import dev.botta.trantor.ai.Cancellation
import kotlin.time.Duration

/** Options of the call itself, shared by every model family. */
data class CallOptions(
    val timeout: Duration? = null,
    val cancellation: Cancellation? = null,
    val headers: Map<String, String> = emptyMap(),
)
