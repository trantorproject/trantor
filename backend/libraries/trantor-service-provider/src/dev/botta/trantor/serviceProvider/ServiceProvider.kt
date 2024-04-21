package dev.botta.trantor.serviceProvider

interface ServiceProvider {
    fun <T: Any> getOrDefault(type: Class<T>, key: String, default: () -> T): T

    fun <T: Any> getOrDefault(type: Class<T>, default: () -> T): T

    fun <T: Any> get(type: Class<T>, key: String? = null): T

    fun <T: Any> getAll(type: Class<T>, key: String? = null): List<T>

    fun <T: Any> tryGet(type: Class<T>, key: String? = null): T?

    fun enterScope()

    fun leaveScope()
}

inline fun <reified T: Any> ServiceProvider.getOrDefault(key: String, noinline default: () -> T) = getOrDefault(T::class.java, key, default)

inline fun <reified T: Any> ServiceProvider.getOrDefault(noinline default: () -> T) = getOrDefault(T::class.java, default)

inline fun <reified T: Any> ServiceProvider.get(key: String? = null) = get(T::class.java, key)

inline fun <reified T: Any> ServiceProvider.getAll(key: String? = null) = getAll(T::class.java, key)

inline fun <reified T: Any> ServiceProvider.tryGet(key: String? = null) = tryGet(T::class.java, key)

