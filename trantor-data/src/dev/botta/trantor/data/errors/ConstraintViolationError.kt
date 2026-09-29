package dev.botta.trantor.data.errors

/**
 * A write broke a constraint of the database. [constraint] and [table] are the names the database uses, as it
 * reports them, and null when it does not: MySQL does not name the table of a unique key, MariaDB does not name
 * the table of a check. A subclass says which kind of constraint it was; this class alone is one the adapter
 * could not tell apart, such as an exclusion constraint of Postgres.
 */
open class ConstraintViolationError(
    val constraint: String?,
    val table: String?,
    message: String = describe("Constraint", constraint, table),
    cause: Throwable? = null,
): DataError(message, cause)

internal fun describe(kind: String, constraint: String?, table: String?) =
    "$kind constraint ${constraint ?: "(unnamed)"}${table?.let { " on $it" } ?: ""} was violated"
