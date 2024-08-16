package dev.botta.trantor.data.jooq

import org.jooq.*
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory

class SQLLogger: ExecuteListener {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)

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
