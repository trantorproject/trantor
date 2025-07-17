package dev.botta.trantor.serviceProvider

import dev.botta.trantor.serviceProvider.ServiceLifetimes.*

typealias ServiceConfiguration<T> = (service: T) -> Unit

@Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
class ServiceRegistry: MutableList<ServiceDescriptor<*>> by mutableListOf() {
    private val configurations: MutableList<ServiceConfigurationItem<*>> = mutableListOf()

    inline fun <reified TService: Any, reified TImplementation: TService> addTransient(key: String? = null) = apply {
        addTransient(TService::class.java, TImplementation::class.java, key)
    }

    inline fun <reified TService: Any> addTransient(key: String? = null, implementationType: Class<TService> = TService::class.java) = apply {
        addTransient(TService::class.java, implementationType, key)
    }

    inline fun <reified TService: Any> addTransient(key: String?, noinline factory: ImplementationFactory<TService>) = apply {
        addTransient(TService::class.java, factory, key)
    }

    inline fun <reified TService: Any> addTransient(noinline factory: ImplementationFactory<TService>) = apply {
        addTransient(TService::class.java, factory)
    }

    fun <TService: Any, TImplementation: TService> addTransient(serviceType: Class<TService>, implementationType: Class<TImplementation>, key: String? = null) = apply {
        addTransient(serviceType, createTypeFactory(implementationType), key)
    }

    fun <TService: Any> addTransient(serviceType: Class<TService>, factory: ImplementationFactory<TService>) = apply {
        addService(serviceType, factory, Transient)
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

    inline fun <reified TService: Any> addScoped(noinline factory: ImplementationFactory<TService>) = apply {
        addScoped(TService::class.java, factory)
    }

    inline fun <reified TService: Any> addScoped(key: String?, noinline factory: ImplementationFactory<TService>) = apply {
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

    inline fun <reified TService: Any> addSingleton(noinline factory: ImplementationFactory<TService>) = apply {
        addSingleton(TService::class.java, factory, null)
    }

    inline fun <reified TService: Any> addSingleton(key: String, implementation: TService) = apply {
        addSingleton(TService::class.java, { implementation }, key)
    }

    inline fun <reified TService: Any> addSingleton(key: String?, noinline factory: ImplementationFactory<TService>) = apply {
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
        add(ServiceDescriptor(serviceType, factory, lifetime, key))
    }

    fun has(serviceType: Class<*>, key: String? = null) =
        any { it.serviceType == serviceType && it.key == key }

    fun ensureAdded(descriptor: ServiceDescriptor<*>) {
        if (any { it.serviceType == descriptor.serviceType && it.key == descriptor.key }) return
        add(descriptor)
    }

    fun <TService: Any> configure(serviceType: Class<TService>, configuration: ServiceConfiguration<TService>) {
        configure(serviceType, null, configuration)
    }

    fun <TService: Any> configure(serviceType: Class<TService>, key: String?, configuration: ServiceConfiguration<TService>) {
        configurations.add(ServiceConfigurationItem(ServiceDescriptor.serviceId(serviceType, key), configuration))
    }

    inline fun <reified TService: Any> has(key: String? = null) = has(TService::class.java, key)

    inline fun <reified TService: Any> configure(key: String, noinline configuration: ServiceConfiguration<TService>) {
        configure(TService::class.java, key, configuration)
    }

    inline fun <reified TService: Any> configure(noinline configuration: ServiceConfiguration<TService>) {
        configure(TService::class.java, configuration)
    }

    @Suppress("UNCHECKED_CAST")
    fun <TService: Any> getConfigurations(serviceType: Class<TService>, key: String? = null): List<ServiceConfiguration<TService>> {
        val serviceId = ServiceDescriptor.serviceId(serviceType, key)
        return configurations.filter { it.serviceId == serviceId }.map { it.configuration as ServiceConfiguration<TService> }
    }

    inline fun <reified TService: Any> getConfigurations(key: String? = null) =
        getConfigurations(TService::class.java, key)

    private fun <T> createTypeFactory(type: Class<T>): ImplementationFactory<T> {
        try {
            val defaultConstructor = type.getDeclaredConstructor()
            return { defaultConstructor.newInstance() }
        } catch (e: NoSuchMethodException) {
            throw MustHaveDefaultNoArgsConstructorError(type)
        }
    }

    data class ServiceConfigurationItem<T: Any>(val serviceId: String, val configuration: ServiceConfiguration<T>)
}
