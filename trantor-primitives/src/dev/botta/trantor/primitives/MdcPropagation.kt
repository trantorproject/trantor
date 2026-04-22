package dev.botta.trantor.primitives

import org.slf4j.MDC

object MdcPropagation {
    fun capture(): Map<String, String> {
        return MDC.getCopyOfContextMap()?.toMap() ?: emptyMap()
    }

    fun <T> runWithContext(context: Map<String, String>, block: () -> T): T {
        val previous = MDC.getCopyOfContextMap()?.toMap()

        try {
            MDC.clear()
            context.forEach { (k, v) -> MDC.put(k, v) }
            return block()
        } finally {
            MDC.clear()
            previous?.forEach { (k, v) -> MDC.put(k, v) }
        }
    }
}
