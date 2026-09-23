package dev.botta.trantor.ai

import kotlin.reflect.KClass
import kotlin.reflect.safeCast

/**
 * Values of the application found by their type: their own class or an interface they implement. There are no keys
 * to misspell and nothing to cast; the price is one value per type, which asks for types of the application's own
 * (`StoreId` and not `Int`).
 *
 * Two values that are the type asked for make the question ambiguous, so it fails naming them instead of answering
 * with whichever came first.
 */
internal class TypedValues(
    /** Who holds them, as the errors name it: "The agent support". */
    private val holder: String,
    private val values: List<Any>,
) {
    fun <T: Any> get(type: KClass<T>): T? {
        val found = values.mapNotNull { type.safeCast(it) }

        require(found.size <= 1) {
            "$holder has more than one ${type.simpleName}: ${found.joinToString { it::class.simpleName.orEmpty() }}. " +
                "Ask for one of those"
        }

        return found.firstOrNull()
    }

    fun <T: Any> require(type: KClass<T>): T = get(type) ?: throw IllegalArgumentException(
        "$holder has no ${type.simpleName}. It has: ${values.joinToString { it::class.simpleName.orEmpty() }.ifEmpty { "nothing" }}",
    )

    /** A class with more than one value, which nobody could ask for without it being ambiguous. */
    fun repeatedClass(): KClass<*>? = values.groupBy { it::class }.entries.firstOrNull { it.value.size > 1 }?.key
}
