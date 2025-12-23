package dev.botta.trantor.core.logging

import org.slf4j.Logger

// Wraps errors to not lose coroutine stack trace
fun Logger.wrappedError(msg: String, t: Throwable) {
    val contextualError = RuntimeException(msg, t)
    error(msg, contextualError)
}
