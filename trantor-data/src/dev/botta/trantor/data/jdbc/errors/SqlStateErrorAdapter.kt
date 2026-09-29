package dev.botta.trantor.data.jdbc.errors

import dev.botta.trantor.data.errors.*
import java.sql.SQLException

/**
 * Classifies an error by its SQLSTATE alone, as the SQL standard defines it, so it serves any database whose
 * driver follows it. It cannot name the constraint, the table or the column: an adapter of the database does.
 */
object SqlStateErrorAdapter: SqlErrorAdapter {
    private const val INTEGRITY_CONSTRAINT_VIOLATION = "23"
    private const val NOT_NULL_VIOLATION = "23502"
    private const val FOREIGN_KEY_VIOLATION = "23503"
    private const val UNIQUE_VIOLATION = "23505"
    private const val CHECK_VIOLATION = "23514"

    override fun translate(error: SQLException, cause: Throwable) =
        translate(error.sqlState, null, null, null, cause)

    internal fun translate(
        sqlState: String?,
        constraint: String?,
        table: String?,
        column: String?,
        cause: Throwable,
    ): DataError? = when {
        sqlState == UNIQUE_VIOLATION -> UniqueViolationError(constraint, table, cause)
        sqlState == FOREIGN_KEY_VIOLATION -> ForeignKeyViolationError(constraint, table, cause)
        sqlState == NOT_NULL_VIOLATION -> NotNullViolationError(column, table, cause)
        sqlState == CHECK_VIOLATION -> CheckViolationError(constraint, table, cause)
        sqlState?.startsWith(INTEGRITY_CONSTRAINT_VIOLATION) == true -> ConstraintViolationError(constraint, table, cause = cause)
        else -> null
    }
}
