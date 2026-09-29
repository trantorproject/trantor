package dev.botta.trantor.data.errors

/** A write left empty [column], which does not take null. MySQL does not name the [table]. */
class NotNullViolationError(val column: String?, table: String?, cause: Throwable? = null):
    ConstraintViolationError(
        null,
        table,
        "Column ${column ?: "(unnamed)"}${table?.let { " of $it" } ?: ""} cannot be null",
        cause,
    )
