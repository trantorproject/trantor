package dev.botta.trantor.data.jdbc.errors

import dev.botta.trantor.data.errors.*
import java.sql.SQLException

/**
 * Classifies an error of MySQL or MariaDB by its error code and reads the names from its message, the only place
 * where these databases give them. The SQLSTATE does not tell the constraints apart: MySQL answers 23000 to all
 * of them and HY000 to a broken check.
 */
object MySqlErrorAdapter: SqlErrorAdapter {
    private const val DUPLICATE_ENTRY = 1062
    private const val COLUMN_CANNOT_BE_NULL = 1048
    private const val FIELD_WITHOUT_DEFAULT = 1364
    private const val ROW_IS_REFERENCED = 1451
    private const val NO_REFERENCED_ROW = 1452
    private const val MYSQL_CHECK_VIOLATED = 3819
    private const val MARIADB_CHECK_VIOLATED = 4025

    private val duplicateKey = Regex("for key '([^']+)'$")
    private val foreignKey = Regex("""\(`[^`]*`\.`([^`]+)`, CONSTRAINT `([^`]+)`""")
    private val column = Regex("""(?:Column|Field) '([^']+)'""")
    private val mySqlCheck = Regex("""Check constraint '([^']+)' is violated""")
    private val mariaDbCheck = Regex("""CONSTRAINT `([^`]+)` failed for `[^`]*`\.`([^`]+)`""")

    override fun translate(error: SQLException, cause: Throwable): DataError? {
        val message = error.message.orEmpty()
        return when (error.errorCode) {
            DUPLICATE_ENTRY -> duplicateEntry(message, cause)
            ROW_IS_REFERENCED, NO_REFERENCED_ROW -> foreignKey.find(message)
                .let { ForeignKeyViolationError(it?.groupValues?.get(2), it?.groupValues?.get(1), cause) }
            COLUMN_CANNOT_BE_NULL, FIELD_WITHOUT_DEFAULT ->
                NotNullViolationError(column.find(message)?.groupValues?.get(1), null, cause)
            MYSQL_CHECK_VIOLATED -> CheckViolationError(mySqlCheck.find(message)?.groupValues?.get(1), null, cause)
            MARIADB_CHECK_VIOLATED -> mariaDbCheck.find(message)
                .let { CheckViolationError(it?.groupValues?.get(1), it?.groupValues?.get(2), cause) }
            else -> SqlStateErrorAdapter.translate(error, cause)
        }
    }

    private fun duplicateEntry(message: String, cause: Throwable): UniqueViolationError {
        val key = duplicateKey.find(message)?.groupValues?.get(1)
            ?: return UniqueViolationError(null, null, cause)
        if (!key.contains('.')) return UniqueViolationError(key, null, cause)
        return UniqueViolationError(key.substringAfter('.'), key.substringBefore('.'), cause)
    }
}
