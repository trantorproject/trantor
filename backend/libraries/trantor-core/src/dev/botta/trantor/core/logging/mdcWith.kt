package dev.botta.trantor.core.logging

import org.slf4j.MDC

fun mdcWith(vararg params: Pair<String, String>): Map<String, String> {
    val current = MDC.getCopyOfContextMap() ?: emptyMap()
    return current + params
}
