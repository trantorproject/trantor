package dev.botta.trantor.data.errors

/**
 * A write broke a foreign key: it pointed to a row that does not exist, or deleted a row that others still point
 * to. [table] is the one that holds the foreign key in both cases, so which of the two it was is known by the
 * operation that failed: a delete that fails with it means the row is still in use.
 */
class ForeignKeyViolationError(constraint: String?, table: String?, cause: Throwable? = null):
    ConstraintViolationError(constraint, table, describe("Foreign key", constraint, table), cause)
