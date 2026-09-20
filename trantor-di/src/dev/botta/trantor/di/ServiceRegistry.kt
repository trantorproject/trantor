package dev.botta.trantor.di

import dev.botta.trantor.config.*
import dev.botta.trantor.di.ServiceLifetimes.*
import dev.botta.trantor.primitives.serialization.JsonSerializer

/**
 * Everything the application registers, before anything is built. A [ServiceProvider] reads it and
 * resolves from it.
 *
 * It is a list of [ServiceDescriptor], in registration order, which is what makes the two rules work:
 * `get<T>()` returns the **last** registration of a type, and `getAll<T>()` returns them all in order.
 *
 * The four ways to put something in:
 *
 * - `addTransient` / `addSingleton` / `addScoped`, by implementation type, by factory or by instance.
 *   Each has an `...IfMissing` twin, which is how an `addX()` extension stays idempotent.
 * - [addConfig], for a settings class that comes from a configuration section.
 * - [configure], to adjust an instance after it is created, or to let a component register itself
 *   somewhere it does not own.
 * - A `key`, to tell apart two registrations of the same type.
 *
 * Registration order between [configure] and the `add...` that creates the instance does not matter:
 * configurations are collected and applied when the instance is built.
 */
// TODO: Que el createTypeFactory use el ServiceProvider.create()
@Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
class ServiceRegistry(val config: ConfigManager): MutableList<ServiceDescriptor<*, *>> by mutableListOf() {
    private val configurations: MutableList<ServiceConfigurationItem<*>> = mutableListOf()

    init {
        addSingleton<Config>(config)
    }

    inline fun <reified TService: Any, reified TImplementation: TService> addTransient(key: String? = null) = apply {
        addTransient(TService::class.java, TImplementation::class.java, key)
    }

    inline fun <reified TService: Any> addTransient(
        key: String? = null,
        implementationType: Class<TService> = TService::class.java,
    ) = apply {
        addTransient(TService::class.java, implementationType, key)
    }

    inline fun <reified TService: Any> addTransient(key: String?, noinline factory: ImplementationFactory<TService>) =
        apply {
            addTransient(TService::class.java, factory, key)
        }

    inline fun <reified TService: Any> addTransient(noinline factory: ImplementationFactory<TService>) = apply {
        addTransient(TService::class.java, factory)
    }

    fun <TService: Any, TImplementation: TService> addTransient(
        serviceType: Class<TService>,
        implementationType: Class<TImplementation>,
        key: String? = null,
    ) = apply {
        addService(serviceType, implementationType, Transient, key)
    }

    fun <TService: Any> addTransient(serviceType: Class<TService>, factory: ImplementationFactory<TService>) = apply {
        addService(serviceType, factory, Transient)
    }

    fun <TService: Any> addTransient(
        serviceType: Class<TService>,
        factory: ImplementationFactory<TService>,
        key: String? = null,
    ) = apply {
        addService(serviceType, factory, Transient, key)
    }

    inline fun <reified TService: Any, reified TImplementation: TService> addTransientIfMissing(key: String? = null) =
        apply {
            addTransientIfMissing(TService::class.java, TImplementation::class.java, key)
        }

    inline fun <reified TService: Any> addTransientIfMissing(
        key: String? = null,
        implementationType: Class<TService> = TService::class.java,
    ) = apply {
        addTransientIfMissing(TService::class.java, implementationType, key)
    }

    inline fun <reified TService: Any> addTransientIfMissing(
        key: String?,
        noinline factory: ImplementationFactory<TService>,
    ) = apply {
        addTransientIfMissing(TService::class.java, factory, key)
    }

    inline fun <reified TService: Any> addTransientIfMissing(noinline factory: ImplementationFactory<TService>) =
        apply {
            addTransientIfMissing(TService::class.java, factory)
        }

    fun <TService: Any, TImplementation: TService> addTransientIfMissing(
        serviceType: Class<TService>,
        implementationType: Class<TImplementation>,
        key: String? = null,
    ) = apply {
        addServiceIfMissing(serviceType, implementationType, Transient, key)
    }

    fun <TService: Any> addTransientIfMissing(serviceType: Class<TService>, factory: ImplementationFactory<TService>) =
        apply {
            addServiceIfMissing(serviceType, factory, Transient)
        }

    fun <TService: Any> addTransientIfMissing(
        serviceType: Class<TService>,
        factory: ImplementationFactory<TService>,
        key: String? = null,
    ) = apply {
        addServiceIfMissing(serviceType, factory, Transient, key)
    }

    inline fun <reified TService: Any, reified TImplementation: TService> addScoped(key: String? = null) = apply {
        addScoped(TService::class.java, TImplementation::class.java, key)
    }

    inline fun <reified TService: Any> addScoped(
        key: String? = null,
        implementationType: Class<TService> = TService::class.java,
    ) = apply {
        addScoped(TService::class.java, implementationType, key)
    }

    inline fun <reified TService: Any> addScoped(noinline factory: ImplementationFactory<TService>) = apply {
        addScoped(TService::class.java, factory)
    }

    inline fun <reified TService: Any> addScoped(key: String?, noinline factory: ImplementationFactory<TService>) =
        apply {
            addScoped(TService::class.java, factory, key)
        }

    fun <TService: Any, TImplementation: TService> addScoped(
        serviceType: Class<TService>,
        implementationType: Class<TImplementation>,
        key: String? = null,
    ) = apply {
        addService(serviceType, implementationType, Scoped, key)
    }

    fun <TService: Any> addScoped(
        serviceType: Class<TService>,
        factory: ImplementationFactory<TService>,
        key: String? = null,
    ) = apply {
        addService(serviceType, factory, Scoped, key)
    }

    inline fun <reified TService: Any, reified TImplementation: TService> addScopedIfMissing(key: String? = null) =
        apply {
            addScopedIfMissing(TService::class.java, TImplementation::class.java, key)
        }

    inline fun <reified TService: Any> addScopedIfMissing(
        key: String? = null,
        implementationType: Class<TService> = TService::class.java,
    ) = apply {
        addScopedIfMissing(TService::class.java, implementationType, key)
    }

    inline fun <reified TService: Any> addScopedIfMissing(noinline factory: ImplementationFactory<TService>) = apply {
        addScopedIfMissing(TService::class.java, factory)
    }

    inline fun <reified TService: Any> addScopedIfMissing(
        key: String?,
        noinline factory: ImplementationFactory<TService>,
    ) = apply {
        addScopedIfMissing(TService::class.java, factory, key)
    }

    fun <TService: Any, TImplementation: TService> addScopedIfMissing(
        serviceType: Class<TService>,
        implementationType: Class<TImplementation>,
        key: String? = null,
    ) = apply {
        addServiceIfMissing(serviceType, implementationType, Scoped, key)
    }

    fun <TService: Any> addScopedIfMissing(
        serviceType: Class<TService>,
        factory: ImplementationFactory<TService>,
        key: String? = null,
    ) = apply {
        addServiceIfMissing(serviceType, factory, Scoped, key)
    }

    inline fun <reified TService: Any, reified TImplementation: TService> addSingleton(key: String? = null) = apply {
        addSingleton(TService::class.java, TImplementation::class.java, key)
    }

    inline fun <reified TService: Any> addSingleton(
        key: String? = null,
        implementationType: Class<TService> = TService::class.java,
    ) = apply {
        addSingleton(TService::class.java, implementationType, key)
    }

    inline fun <reified TService: Any> addSingleton(instance: TService, key: String? = null) = apply {
        addSingleton(TService::class.java, instance, key)
    }

    inline fun <reified TService: Any> addSingleton(noinline factory: ImplementationFactory<TService>) = apply {
        addSingleton(TService::class.java, factory, null)
    }

    inline fun <reified TService: Any> addSingleton(key: String, instance: TService) = apply {
        addSingleton(TService::class.java, instance, key)
    }

    inline fun <reified TService: Any> addSingleton(key: String?, noinline factory: ImplementationFactory<TService>) =
        apply {
            addSingleton(TService::class.java, factory, key)
        }

    fun <TService: Any> addSingleton(serviceType: Class<TService>, instance: TService, key: String? = null) = apply {
        addService(serviceType, instance, key)
    }

    fun <TService: Any, TImplementation: TService> addSingleton(
        serviceType: Class<TService>,
        implementationType: Class<TImplementation>,
        key: String? = null,
    ) = apply {
        addService(serviceType, implementationType, Singleton, key)
    }

    fun <TService: Any> addSingleton(
        serviceType: Class<TService>,
        factory: ImplementationFactory<TService>,
        key: String? = null,
    ) = apply {
        addService(serviceType, factory, Singleton, key)
    }

    inline fun <reified TService: Any, reified TImplementation: TService> addSingletonIfMissing(key: String? = null) =
        apply {
            addSingletonIfMissing(TService::class.java, TImplementation::class.java, key)
        }

    inline fun <reified TService: Any> addSingletonIfMissing(
        key: String? = null,
        implementationType: Class<TService> = TService::class.java,
    ) = apply {
        addSingletonIfMissing(TService::class.java, implementationType, key)
    }

    inline fun <reified TService: Any> addSingletonIfMissing(instance: TService, key: String? = null) = apply {
        addSingletonIfMissing(TService::class.java, instance, key)
    }

    inline fun <reified TService: Any> addSingletonIfMissing(noinline factory: ImplementationFactory<TService>) =
        apply {
            addSingletonIfMissing(TService::class.java, factory, null)
        }

    inline fun <reified TService: Any> addSingletonIfMissing(key: String, instance: TService) = apply {
        addSingletonIfMissing(TService::class.java, instance, key)
    }

    inline fun <reified TService: Any> addSingletonIfMissing(
        key: String?,
        noinline factory: ImplementationFactory<TService>,
    ) = apply {
        addSingletonIfMissing(TService::class.java, factory, key)
    }

    fun <TService: Any> addSingletonIfMissing(serviceType: Class<TService>, instance: TService, key: String? = null) =
        apply {
            addServiceIfMissing(serviceType, instance, key)
        }

    fun <TService: Any, TImplementation: TService> addSingletonIfMissing(
        serviceType: Class<TService>,
        implementationType: Class<TImplementation>,
        key: String? = null,
    ) = apply {
        addServiceIfMissing(serviceType, implementationType, Singleton, key)
    }

    fun <TService: Any> addSingletonIfMissing(
        serviceType: Class<TService>,
        factory: ImplementationFactory<TService>,
        key: String? = null,
    ) = apply {
        addServiceIfMissing(serviceType, factory, Singleton, key)
    }

    private fun <TService: Any> addService(
        serviceType: Class<TService>,
        factory: ImplementationFactory<TService>,
        lifetime: ServiceLifetimes,
        key: String? = null,
    ) = apply {
        add(
            ServiceDescriptor.createWithImplementationFactory(
                serviceType,
                implementationFactory = factory,
                lifetime = lifetime,
                key = key
            )
        )
    }

    private fun <TService: Any, TImplementation: TService> addService(
        serviceType: Class<TService>,
        implementationType: Class<TImplementation>,
        lifetime: ServiceLifetimes,
        key: String? = null,
    ) = apply {
        add(
            ServiceDescriptor.createWithImplementationType(
                serviceType,
                implementationType = implementationType,
                lifetime = lifetime,
                key = key
            )
        )
    }

    private fun <TService: Any> addService(serviceType: Class<TService>, instance: TService, key: String? = null) =
        apply {
            add(ServiceDescriptor.createWithInstance(serviceType, instance = instance, lifetime = Singleton, key = key))
        }

    private fun <TService: Any> addServiceIfMissing(
        serviceType: Class<TService>,
        factory: ImplementationFactory<TService>,
        lifetime: ServiceLifetimes,
        key: String? = null,
    ) = apply {
        if (has(serviceType, key)) return@apply
        addService(serviceType, factory, lifetime, key)
    }

    private fun <TService: Any, TImplementation: TService> addServiceIfMissing(
        serviceType: Class<TService>,
        implementationType: Class<TImplementation>,
        lifetime: ServiceLifetimes,
        key: String? = null,
    ) = apply {
        if (has(serviceType, key)) return@apply
        addService(serviceType, implementationType, lifetime, key)
    }

    private fun <TService: Any> addServiceIfMissing(
        serviceType: Class<TService>,
        instance: TService,
        key: String? = null,
    ) = apply {
        if (has(serviceType, key)) return@apply
        addService(serviceType, instance, key)
    }

    /** Whether something is already registered for that type and key. The guard of an idempotent `addX()`. */
    fun has(serviceType: Class<*>, key: String? = null) =
        any { it.serviceType == serviceType && it.key == key }

    /**
     * Runs [configuration] on the instance right after it is created, in registration order.
     *
     * Two uses. Adjusting what something was built with:
     *
     * ```
     * configure<OpenAIConfig> { openAI, _ -> openAI.apiKey = "sk-..." }
     * ```
     *
     * And letting a component add itself to a registry it does not own, which is how a provider becomes
     * available without anyone collecting it with `getAll`:
     *
     * ```
     * configure<ModelRegistry> { models, services -> models.addProvider(services.get<OpenAIProvider>()) }
     * ```
     *
     * When it runs depends on the lifetime: once for a singleton, once per scope for a scoped service,
     * and on every resolution for a transient one.
     */
    fun <TService: Any> configure(serviceType: Class<TService>, configuration: ServiceConfiguration<TService>) {
        configure(serviceType, null, configuration)
    }

    fun <TService: Any> configure(
        serviceType: Class<TService>,
        key: String?,
        configuration: ServiceConfiguration<TService>,
    ) {
        configurations.add(ServiceConfigurationItem(ServiceDescriptor.serviceId(serviceType, key), configuration))
    }

    inline fun <reified TService: Any> has(key: String? = null) = has(TService::class.java, key)

    inline fun <reified TService: Any> configure(key: String, noinline configuration: ServiceConfiguration<TService>) {
        configure(TService::class.java, key, configuration)
    }

    inline fun <reified TService: Any> configure(noinline configuration: ServiceConfiguration<TService>) {
        configure(TService::class.java, configuration)
    }

    /**
     * Registers a settings class read from a whole configuration section, deserialized with the
     * [JsonSerializer] in the container.
     *
     * ```
     * addConfig<JdbcSettings>("jdbc")
     * ```
     *
     * A settings class is **never** read field by field from [config]. When the section is missing, or has
     * no usable JSON, `{}` is deserialized instead — so **Kotlin default values are the real defaults** and
     * do not have to be repeated anywhere. That is why a settings class is a data class of `var` properties
     * with a default for each one.
     *
     * The instance can still be adjusted afterwards with [configure], whatever the order of the two calls.
     */
    fun <TService: Any> addConfig(serviceType: Class<TService>, configSection: String, key: String? = null) = apply {
        addSingleton(
            serviceType,
            {
                val jsonSerializer = it.get<JsonSerializer>()
                if (!it.config.hasSection(configSection)) return@addSingleton jsonSerializer.deserialize(
                    "{}",
                    serviceType
                )
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
    fun <TService: Any> getConfigurations(
        serviceType: Class<TService>,
        key: String? = null,
    ): List<ServiceConfiguration<TService>> {
        val serviceId = ServiceDescriptor.serviceId(serviceType, key)
        return configurations.filter { it.serviceId == serviceId }
            .map { it.configuration as ServiceConfiguration<TService> }
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
