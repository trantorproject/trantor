package dev.botta.trantor.primitives

import io.opentelemetry.context.Context

/**
 * Carries what a thread knows about the work it is doing to another thread: the logging context ([MdcPropagation])
 * and the OpenTelemetry context, which holds the current span.
 *
 * Both live in thread locals, so work handed to another thread starts without them: its logs lose the correlation
 * id and its spans start a trace of their own. Capture on the thread that hands the work over, and run the work
 * with what was captured.
 *
 * ```kotlin
 * val context = ContextPropagation.capture()
 * executor.submit { ContextPropagation.runWithContext(context) { work() } }
 * ```
 *
 * Without an OpenTelemetry SDK installed there is no span to carry, and only the logging context travels.
 */
object ContextPropagation {
    fun capture() = PropagatedContext(MdcPropagation.capture(), Context.current())

    /** Runs [block] with [context], and gives the thread back what it had, even when the block fails. */
    fun <T> runWithContext(context: PropagatedContext, block: () -> T): T =
        context.otel.makeCurrent().use { MdcPropagation.runWithContext(context.mdc, block) }
}

/** What [ContextPropagation.capture] took from a thread. It does not change when that thread moves on. */
class PropagatedContext internal constructor(internal val mdc: Map<String, String>, internal val otel: Context)
