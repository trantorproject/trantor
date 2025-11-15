package dev.botta.trantor.data.jooq.coroutines

import dev.botta.trantor.data.coroutines.DbDispatcherProvider
import kotlinx.coroutines.withContext
import org.jooq.DSLContext

class JooqScope(val dsl: DSLContext, private val dispatcherProvider: DbDispatcherProvider) {
    suspend operator fun <T> invoke(block: suspend DSLContext.() -> T): T =
        withContext(dispatcherProvider.get()) { dsl.block() }
}
