package dev.botta.trantor.ai.telemetry

import dev.botta.trantor.primitives.logging.getLogger

private val logger = getLogger("dev.botta.trantor.ai.telemetry")

/**
 * Runs a piece of the telemetry of the models, which never fails what it watches: what it cannot do is logged, and
 * the work goes on without it.
 */
internal fun <T> safely(what: String, block: () -> T): T? = try {
    block()
} catch (e: Exception) {
    logger.warn("Could not $what for the telemetry of the models, going on without it", e)
    null
}
