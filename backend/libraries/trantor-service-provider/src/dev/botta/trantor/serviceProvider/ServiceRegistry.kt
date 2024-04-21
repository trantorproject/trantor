package dev.botta.trantor.serviceProvider

import dev.botta.trantor.serviceProvider.ServiceLifetimes.*

class ServiceRegistry: MutableList<ServiceDescriptor<*>> by mutableListOf() {
    inline fun <reified TService: Any, reified TImplementation: TService> addTransient(key: String? = null) = apply {
        addTransient(TService::class.java, TImplementation::class.java, key)
    }

    inline fun <reified TService: Any> addTransient(key: String? = null, implementationType: Class<TService> = TService::class.java) = apply {
        addTransient(TService::class.java, implementationType, key)
    }

    inline fun <reified TService: Any> addTransient(noinline factory: ImplementationFactory<TService>, key: String? = null) = apply {
        addTransient(TService::class.java, factory, key)
    }

    inline fun <reified TService: Any> addTransient(key: String, noinline factory: ImplementationFactory<TService>) = apply {
        addTransient(TService::class.java, factory, key)
    }

    fun <TService: Any, TImplementation: TService> addTransient(serviceType: Class<TService>, implementationType: Class<TImplementation>, key: String? = null) = apply {
        addTransient(serviceType, createTypeFactory(implementationType), key)
    }

    fun <TService: Any> addTransient(serviceType: Class<TService>, factory: ImplementationFactory<TService>, key: String? = null) = apply {
        addService(serviceType, factory, Transient, key)
    }

    inline fun <reified TService: Any, reified TImplementation: TService> addScoped(key: String? = null) = apply {
        addScoped(TService::class.java, TImplementation::class.java, key)
    }

    inline fun <reified TService: Any> addScoped(key: String? = null, implementationType: Class<TService> = TService::class.java) = apply {
        addScoped(TService::class.java, implementationType, key)
    }

    inline fun <reified TService: Any> addScoped(noinline factory: ImplementationFactory<TService>, key: String? = null) = apply {
        addScoped(TService::class.java, factory, key)
    }

    inline fun <reified TService: Any> addScoped(key: String, noinline factory: ImplementationFactory<TService>) = apply {
        addScoped(TService::class.java, factory, key)
    }

    fun <TService: Any, TImplementation: TService> addScoped(serviceType: Class<TService>, implementationType: Class<TImplementation>, key: String? = null) = apply {
        addScoped(serviceType, createTypeFactory(implementationType), key)
    }

    fun <TService: Any> addScoped(serviceType: Class<TService>, factory: ImplementationFactory<TService>, key: String? = null) = apply {
        addService(serviceType, factory, Scoped, key)
    }

    inline fun <reified TService: Any, reified TImplementation: TService> addSingleton(key: String? = null) = apply {
        addSingleton(TService::class.java, TImplementation::class.java, key)
    }

    inline fun <reified TService: Any> addSingleton(key: String? = null, implementationType: Class<TService> = TService::class.java) = apply {
        addSingleton(TService::class.java, implementationType, key)
    }

    inline fun <reified TService: Any> addSingleton(implementation: TService, key: String? = null) = apply {
        addSingleton(TService::class.java, { implementation }, key)
    }

    inline fun <reified TService: Any> addSingleton(noinline factory: ImplementationFactory<TService>, key: String? = null) = apply {
        addSingleton(TService::class.java, factory, key)
    }

    inline fun <reified TService: Any> addSingleton(key: String, implementation: TService) = apply {
        addSingleton(TService::class.java, { implementation }, key)
    }

    inline fun <reified TService: Any> addSingleton(key: String, noinline factory: ImplementationFactory<TService>) = apply {
        addSingleton(TService::class.java, factory, key)
    }

    fun <TService: Any, TImplementation: TService> addSingleton(serviceType: Class<TService>, implementationType: Class<TImplementation>, key: String? = null) = apply {
        addSingleton(serviceType, createTypeFactory(implementationType), key)
    }

    fun <TService: Any> addSingleton(serviceType: Class<TService>, factory: ImplementationFactory<TService>, key: String? = null) = apply {
        addService(serviceType, factory, Singleton, key)
    }

    private fun <TService: Any> addService(
        serviceType: Class<TService>,
        factory: ImplementationFactory<TService>,
        lifetime: ServiceLifetimes,
        key: String? = null
    ) = apply {
        removeIf { it.serviceType == serviceType && it.key == key }
        add(ServiceDescriptor(serviceType, factory, lifetime, key))
    }

    fun tryAdd(descriptor: ServiceDescriptor<*>) {
        if (any { it.serviceType == descriptor.serviceType && it.key == descriptor.key }) return
        add(descriptor)
    }

    private fun <T> createTypeFactory(type: Class<T>): ImplementationFactory<T> {
        try {
            val defaultConstructor = type.getDeclaredConstructor()
            return { defaultConstructor.newInstance() }
        } catch (e: NoSuchMethodException) {
            throw MustHaveDefaultNoArgsConstructorError(type)
        }
    }
}
