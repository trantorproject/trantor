package dev.botta.trantor.data.jooq.coroutines

import dev.botta.trantor.data.coroutines.DbDispatcherProvider
import kotlinx.coroutines.withContext
import org.jooq.DSLContext

class JooqScope(val dsl: DSLContext, private val dispatcherProvider: DbDispatcherProvider) {
    suspend operator fun <T> invoke(block: suspend DSLContext.() -> T): T {
        // Hack para reconstruir el stack trace (es caro a nivel Garbage Collector, no speed)
        val callSiteException = CallSiteException()
        return try {
            withContext(dispatcherProvider.get()) { dsl.block() }
        } catch (e: Throwable) {
            e.addSuppressed(callSiteException)
            throw e
        }
    }

    private class CallSiteException : Exception(
        "Async call site (suspend boundary inside JooqScope)",
        null, // cause
        false, // enableSupression
        true, // writableStackTrace
    ) {
        override fun toString(): String = message!!
    }
}
