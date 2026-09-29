package dev.botta.trantor.data.jooq

import dev.botta.trantor.data.jdbc.errors.*
import org.jooq.*

/**
 * Turns the errors of the driver into the [dev.botta.trantor.data.errors.DataError]s of trantor-data, with the
 * adapter of the dialect: Postgres, MySQL and MariaDB name the constraint, any other database is classified by
 * its SQLSTATE. What no adapter recognizes stays the exception of jOOQ, which is also the cause of the error it
 * translates, so the SQL that failed is still in the log. `addJooq()` registers it unless `jooq.translateErrors`
 * is false.
 */
class JooqErrorTranslator: ExecuteListener {
    override fun exception(ctx: ExecuteContext) {
        val sqlException = ctx.sqlException() ?: return
        val innermost = generateSequence(sqlException) { it.nextException }.last()
        val error = adapterFor(ctx.dialect()).translate(innermost, ctx.exception() ?: sqlException) ?: return
        ctx.exception(error)
    }

    private fun adapterFor(dialect: SQLDialect) = when (dialect.family()) {
        SQLDialect.POSTGRES -> PostgresErrorAdapter
        SQLDialect.MYSQL, SQLDialect.MARIADB -> MySqlErrorAdapter
        else -> SqlStateErrorAdapter
    }
}
