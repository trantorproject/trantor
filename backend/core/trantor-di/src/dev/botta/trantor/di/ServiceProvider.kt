package dev.botta.trantor.di

import kotlin.reflect.KClass

abstract class ServiceProvider(protected val registry: ServiceRegistry) {
    val config = registry.config

    abstract fun <T: Any> getOrDefault(type: Class<T>, key: String? = null, default: () -> T): T

    abstract fun <T: Any> getOrDefault(type: Class<T>, default: () -> T): T

    abstract fun <T: Any> get(type: Class<T>, key: String? = null): T

    abstract fun <T: Any> getAll(type: Class<T>, key: String? = null): List<T>

    abstract fun <T: Any> tryGet(type: Class<T>, key: String? = null): T?

    abstract fun <T: Any> has(type: Class<T>, key: String? = null): Boolean

    abstract fun enterScope()

    abstract fun leaveScope()

    abstract fun <T : Any> create(type: KClass<T>): T

    inline fun <reified T: Any> get(key: String? = null) = get(T::class.java, key)

    inline fun <reified T: Any> getOrDefault(key: String? = null, noinline default: () -> T) = getOrDefault(T::class.java, key, default)

    inline fun <reified T: Any> getOrDefault(noinline default: () -> T) = getOrDefault(T::class.java, default)

    inline fun <reified T: Any> getAll(key: String? = null) = getAll(T::class.java, key)

    inline fun <reified T: Any> tryGet(key: String? = null) = tryGet(T::class.java, key)

    inline fun <reified T: Any> has(key: String? = null) = has(T::class.java, key)

    inline fun <reified T : Any> create(): T = create(T::class)
}
