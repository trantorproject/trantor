package dev.botta.trantor.serviceProvider

interface ServiceProvider {
    fun <T: Any> getOrDefault(type: Class<T>, key: String, default: () -> T): T

    fun <T: Any> getOrDefault(type: Class<T>, default: () -> T): T

    fun <T: Any> get(type: Class<T>, key: String? = null): T

    fun <T: Any> tryGet(type: Class<T>, key: String? = null): T?

    fun enterScope()

    fun leaveScope()
}
