package dev.botta.trantor.ai

import kotlin.reflect.KClass
import kotlin.reflect.safeCast

/**
 * Values of the application that whoever starts a generation hands to its tools, found by type:
 *
 * ```kotlin
 * ai.generate { context(RunContext(AssistantScope(organizationId))); ... }
 *
 * // inside a tool
 * val scope = context.run.require<AssistantScope>()
 * ```
 *
 * It is how a tool learns who it acts for without a global or a thread local. The values are the application's own
 * types, so the framework knows nothing about tenants or users.
 */
class RunContext(vararg values: Any) {
    private val values = values.toList()

    inline fun <reified T: Any> get(): T? = get(T::class)

    inline fun <reified T: Any> require(): T = require(T::class)

    /** The first value that is a [type], its own class or an interface it implements. */
    fun <T: Any> get(type: KClass<T>): T? = values.firstNotNullOfOrNull { type.safeCast(it) }

    fun <T: Any> require(type: KClass<T>): T = get(type) ?: error(
        "There is no ${type.simpleName} in the run context. " +
            "It has: ${values.joinToString { it::class.simpleName.orEmpty() }.ifEmpty { "nothing" }}",
    )
}
