package dev.botta.trantor.data.jooq

import dev.botta.trantor.core.logging.getLogger
import org.jooq.*
import org.jooq.conf.Settings
import org.jooq.impl.DSL

class SQLLogger: ExecuteListener {
    private val logger = getLogger()

    override fun executeStart(ctx: ExecuteContext) {
        val dsl: DSLContext = DSL.using(
            ctx.dialect(),
            Settings().withRenderFormatted(false)
        )

        if (ctx.query() != null) {
            logger.info(dsl.renderInlined(ctx.query()))
        } else if (ctx.routine() != null) {
            logger.info(dsl.renderInlined(ctx.routine()))
        } else if (ctx.batchQueries().isNotEmpty()) {
            ctx.batchSQL().forEach { logger.info(it) }
        }
    }
}
