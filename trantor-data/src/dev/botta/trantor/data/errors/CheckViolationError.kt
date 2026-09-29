package dev.botta.trantor.data.errors

/** A write broke a check constraint. MySQL does not name the [table]. */
class CheckViolationError(constraint: String?, table: String?, cause: Throwable? = null):
    ConstraintViolationError(constraint, table, describe("Check", constraint, table), cause)
