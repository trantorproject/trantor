package dev.botta.trantor.serviceProvider

import dev.botta.trantor.serviceProvider.ServiceLifetimes.*

class DefaultServiceProvider(private val registry: ServiceRegistry): ServiceProvider {
    private val singletonCache: MutableMap<String, Any> = mutableMapOf()
    private var inScope: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
    private val scopeCache: ThreadLocal<MutableMap<String, Any>> = ThreadLocal.withInitial { mutableMapOf() }

    override fun <T: Any> getOrDefault(type: Class<T>, key: String, default: () -> T) = tryGet(type, key) ?: default()

    override fun <T: Any> getOrDefault(type: Class<T>, default: () -> T) = tryGet(type) ?: default()

    override fun <T: Any> get(type: Class<T>, key: String?) =
        tryGet(type, key) ?: throw ServiceNotRegisteredError(type, key)

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    override fun <T: Any> tryGet(type: Class<T>, key: String?): T? {
        val descriptor = registry.singleOrNull { it.serviceType == type && it.key == key } ?: return null
        return when(descriptor.lifetime) {
            Transient -> descriptor.createInstance()
            Singleton -> singletonCache.getOrPut(serviceId(type, key)) { descriptor.createInstance() }
            Scoped -> {
                if (inScope.get() == false) return null
                scopeCache.get().getOrPut(serviceId(type, key)) { descriptor.createInstance() }
            }
        } as T
    }

    private fun ServiceDescriptor<*>.createInstance(): Any {
        return implementationFactory(this@DefaultServiceProvider) as Any
    }

    override fun enterScope() {
        scopeCache.get().clear()
        inScope.set(true)
    }

    override fun leaveScope() {
        scopeCache.get().clear()
        inScope.set(false)
    }

    private fun serviceId(type: Class<*>, key: String?): String {
        return if (key === null) type.name else type.name + "." + key
    }
}
