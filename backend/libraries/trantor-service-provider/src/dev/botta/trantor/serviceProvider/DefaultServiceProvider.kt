package dev.botta.trantor.serviceProvider

class DefaultServiceProvider(private val registry: ServiceRegistry): ServiceProvider {
    override fun <T: Any> getOrDefault(type: Class<T>, key: String, default: () -> T) = tryGet(type, key) ?: default()

    override fun <T: Any> getOrDefault(type: Class<T>, default: () -> T) = tryGet(type) ?: default()

    override fun <T: Any> get(type: Class<T>, key: String?): T {
        return tryGet(type, key) ?: throw Exception("Service not registered")
    }

    override fun <T: Any> tryGet(type: Class<T>, key: String?): T? {
        TODO()
    }

    override fun enterScope() {
        TODO("Not yet implemented")
    }

    override fun leaveScope() {
        TODO("Not yet implemented")
    }
}
