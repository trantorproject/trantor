package dev.botta.trantor.data.jdbc.errors

import dev.botta.trantor.data.errors.DataError
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import java.sql.SQLException

/**
 * Reads what failed from the fields of the error that Postgres sends, which do not change with the language of
 * the server, as its message does. Needs the driver of Postgres (`org.postgresql:postgresql`), which an
 * application on Postgres already has.
 */
object PostgresErrorAdapter: SqlErrorAdapter {
    override fun translate(error: SQLException, cause: Throwable): DataError? {
        val server: ServerErrorMessage = (error as? PSQLException)?.serverErrorMessage
            ?: return SqlStateErrorAdapter.translate(error, cause)
        return SqlStateErrorAdapter.translate(server.sqlState, server.constraint, server.table, server.column, cause)
    }
}
