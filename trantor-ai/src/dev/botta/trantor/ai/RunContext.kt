package dev.botta.trantor.ai

import kotlin.reflect.KClass

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
 * types, so the framework knows nothing about tenants or users. An agent carries values the same way.
 */
class RunContext(vararg values: Any) {
    private val values = TypedValues("The run context", values.toList())

    inline fun <reified T: Any> get(): T? = get(T::class)

    inline fun <reified T: Any> require(): T = require(T::class)

    /** The value that is a [type], its own class or an interface it implements; it fails when more than one is. */
    fun <T: Any> get(type: KClass<T>): T? = values.get(type)

    fun <T: Any> require(type: KClass<T>): T = values.require(type)
}
