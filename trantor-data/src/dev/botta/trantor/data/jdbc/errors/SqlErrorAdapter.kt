package dev.botta.trantor.data.jdbc.errors

import dev.botta.trantor.data.errors.DataError
import java.sql.SQLException

/**
 * Turns an error of the JDBC driver of one database into a [DataError], naming what that database names, or
 * returns null when it is not an error it knows. [cause] is what the [DataError] keeps as its cause, the error
 * itself unless the caller has one that says more (jOOQ's, which carries the SQL).
 */
interface SqlErrorAdapter {
    fun translate(error: SQLException, cause: Throwable = error): DataError?
}
