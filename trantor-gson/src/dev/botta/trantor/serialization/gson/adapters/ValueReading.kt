package dev.botta.trantor.serialization.gson.adapters

import com.google.gson.JsonParseException
import java.time.DateTimeException

/**
 * Reads [text] as a value of [typeName] with [read], turning what fails to parse into a [JsonParseException]: an id
 * that is not a uuid or a date that is not a date is input that cannot be read, which the web answers with 400. A
 * domain error, like an email that does not exist, goes on as it is.
 */
internal inline fun <T> readValue(text: String, typeName: String, read: () -> T): T = try {
    read()
} catch (e: IllegalArgumentException) {
    throw JsonParseException("'$text' is not a valid $typeName: ${e.message}", e)
} catch (e: DateTimeException) {
    throw JsonParseException("'$text' is not a valid $typeName: ${e.message}", e)
}
