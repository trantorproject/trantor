package dev.botta.trantor.di

import dev.botta.trantor.config.Config
import dev.botta.trantor.core.serialization.JsonSerializer
import dev.botta.trantor.di.ServiceLifetimes.*

@Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
class ServiceRegistry(val config: Config): MutableList<ServiceDescriptor<*>> by mutableListOf() {
    private val configurations: MutableList<ServiceConfigurationItem<*>> = mutableListOf()

    init {
        addSingleton<Config>(config)
    }

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

    inline fun <reified TService: Any, reified TImplementation: TService> addTransientIfMissing(key: String? = null) = apply {
        addTransientIfMissing(TService::class.java, TImplementation::class.java, key)
    }

    inline fun <reified TService: Any> addTransientIfMissing(key: String? = null, implementationType: Class<TService> = TService::class.java) = apply {
        addTransientIfMissing(TService::class.java, implementationType, key)
    }

    inline fun <reified TService: Any> addTransientIfMissing(key: String?, noinline factory: ImplementationFactory<TService>) = apply {
        addTransientIfMissing(TService::class.java, factory, key)
    }

    inline fun <reified TService: Any> addTransientIfMissing(noinline factory: ImplementationFactory<TService>) = apply {
        addTransientIfMissing(TService::class.java, factory)
    }

    fun <TService: Any, TImplementation: TService> addTransientIfMissing(serviceType: Class<TService>, implementationType: Class<TImplementation>, key: String? = null) = apply {
        addTransientIfMissing(serviceType, createTypeFactory(implementationType), key)
    }

    fun <TService: Any> addTransientIfMissing(serviceType: Class<TService>, factory: ImplementationFactory<TService>) = apply {
        addServiceIfMissing(serviceType, factory, Transient)
    }

    fun <TService: Any> addTransientIfMissing(serviceType: Class<TService>, factory: ImplementationFactory<TService>, key: String? = null) = apply {
        addServiceIfMissing(serviceType, factory, Transient, key)
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

    inline fun <reified TService: Any, reified TImplementation: TService> addScopedIfMissing(key: String? = null) = apply {
        addScopedIfMissing(TService::class.java, TImplementation::class.java, key)
    }

    inline fun <reified TService: Any> addScopedIfMissing(key: String? = null, implementationType: Class<TService> = TService::class.java) = apply {
        addScopedIfMissing(TService::class.java, implementationType, key)
    }

    inline fun <reified TService: Any> addScopedIfMissing(noinline factory: ImplementationFactory<TService>) = apply {
        addScopedIfMissing(TService::class.java, factory)
    }

    inline fun <reified TService: Any> addScopedIfMissing(key: String?, noinline factory: ImplementationFactory<TService>) = apply {
        addScopedIfMissing(TService::class.java, factory, key)
    }

    fun <TService: Any, TImplementation: TService> addScopedIfMissing(serviceType: Class<TService>, implementationType: Class<TImplementation>, key: String? = null) = apply {
        addScopedIfMissing(serviceType, createTypeFactory(implementationType), key)
    }

    fun <TService: Any> addScopedIfMissing(serviceType: Class<TService>, factory: ImplementationFactory<TService>, key: String? = null) = apply {
        addServiceIfMissing(serviceType, factory, Scoped, key)
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

    inline fun <reified TService: Any, reified TImplementation: TService> addSingletonIfMissing(key: String? = null) = apply {
        addSingletonIfMissing(TService::class.java, TImplementation::class.java, key)
    }

    inline fun <reified TService: Any> addSingletonIfMissing(key: String? = null, implementationType: Class<TService> = TService::class.java) = apply {
        addSingletonIfMissing(TService::class.java, implementationType, key)
    }

    inline fun <reified TService: Any> addSingletonIfMissing(implementation: TService, key: String? = null) = apply {
        addSingletonIfMissing(TService::class.java, { implementation }, key)
    }

    inline fun <reified TService: Any> addSingletonIfMissing(noinline factory: ImplementationFactory<TService>) = apply {
        addSingletonIfMissing(TService::class.java, factory, null)
    }

    inline fun <reified TService: Any> addSingletonIfMissing(key: String, implementation: TService) = apply {
        addSingletonIfMissing(TService::class.java, { implementation }, key)
    }

    inline fun <reified TService: Any> addSingletonIfMissing(key: String?, noinline factory: ImplementationFactory<TService>) = apply {
        addSingletonIfMissing(TService::class.java, factory, key)
    }

    fun <TService: Any, TImplementation: TService> addSingletonIfMissing(serviceType: Class<TService>, implementationType: Class<TImplementation>, key: String? = null) = apply {
        addSingletonIfMissing(serviceType, createTypeFactory(implementationType), key)
    }

    fun <TService: Any> addSingletonIfMissing(serviceType: Class<TService>, factory: ImplementationFactory<TService>, key: String? = null) = apply {
        addServiceIfMissing(serviceType, factory, Singleton, key)
    }

    private fun <TService: Any> addService(
        serviceType: Class<TService>,
        factory: ImplementationFactory<TService>,
        lifetime: ServiceLifetimes,
        key: String? = null
    ) = apply {
        add(ServiceDescriptor(serviceType, factory, lifetime, key))
    }

    private fun <TService: Any> addServiceIfMissing(
        serviceType: Class<TService>,
        factory: ImplementationFactory<TService>,
        lifetime: ServiceLifetimes,
        key: String? = null
    ) = apply {
        if (has(serviceType, key)) return@apply
        add(ServiceDescriptor(serviceType, factory, lifetime, key))
    }

    fun has(serviceType: Class<*>, key: String? = null) =
        any { it.serviceType == serviceType && it.key == key }

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

    fun <TService: Any> addConfig(serviceType: Class<TService>, configSection: String, key: String? = null) = apply {
        addSingleton(
            serviceType,
            {
                val jsonSerializer = it.get<JsonSerializer>()
                if (!it.config.hasSection(configSection)) return@addSingleton jsonSerializer.deserialize("{}", serviceType)
                val section = it.config.getSection(configSection)
                val json = section.toJson()
                if (json.isNull) return@addSingleton jsonSerializer.deserialize("{}", serviceType)
                jsonSerializer.deserialize(json.toString(), serviceType)
            },
            key,
        )
    }

    inline fun <reified TService: Any> addConfig(configSection: String, key: String? = null) = apply {
        addConfig(TService::class.java, configSection, key)
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
