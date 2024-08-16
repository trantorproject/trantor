package dev.botta.trantor.domain.repositories

import kotlin.reflect.KClass

abstract class RepositoryProvider {
    private val cache = mutableMapOf<KClass<*>, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T: Any> get(repositoryType: KClass<T>) = cache.getOrPut(repositoryType) { create(repositoryType) } as T

    protected abstract fun <T: Any> create(repositoryType: KClass<T>): T

    inline fun <reified T: Any> get() = get(T::class)
}
