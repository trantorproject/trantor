package dev.botta.trantor.data.errors

/**
 * A failure of the database that an application may want to tell apart from the rest, such as a broken
 * constraint. `addJooq()` translates the errors of the driver into these; anything it cannot classify stays
 * the exception of jOOQ.
 */
open class DataError(message: String, cause: Throwable? = null): RuntimeException(message, cause)
