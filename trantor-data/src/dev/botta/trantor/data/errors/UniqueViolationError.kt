package dev.botta.trantor.data.errors

/** A write repeated a value that a unique constraint or the primary key of [table] does not allow twice. */
class UniqueViolationError(constraint: String?, table: String?, cause: Throwable? = null):
    ConstraintViolationError(constraint, table, describe("Unique", constraint, table), cause)
