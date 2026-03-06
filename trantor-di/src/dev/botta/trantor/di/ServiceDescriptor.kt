package dev.botta.trantor.di

import dev.botta.trantor.di.ServiceLifetimes.Singleton

class ServiceDescriptor<T: Any, TImplementation: T> private constructor (
    val serviceType: Class<T>,
    val implementationFactory: ImplementationFactory<T>? = null,
    val implementationType: Class<TImplementation>? = null,
    val instance: T? = null,
    val lifetime: ServiceLifetimes = Singleton,
    val key: String? = null,
) {
    val serviceId = serviceId(serviceType, key)
    val implementationId = buildImplementationId()

    private fun buildImplementationId(): String {
        val base = when {
            implementationType != null -> "type:${implementationType.name}"

            instance != null -> "instance:${System.identityHashCode(instance)}"

            implementationFactory != null -> "factory:${System.identityHashCode(implementationFactory)}"

            else -> error("Invalid ServiceDescriptor: no implementation defined for $serviceType")
        }

        return if (key == null) base else "$base@$key"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ServiceDescriptor<*, *>

        if (serviceType != other.serviceType) return false
        if (implementationFactory != other.implementationFactory) return false
        if (implementationType != other.implementationType) return false
        if (instance != other.instance) return false
        if (lifetime != other.lifetime) return false
        if (key != other.key) return false
        if (serviceId != other.serviceId) return false
        if (implementationId != other.implementationId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = serviceType.hashCode()
        result = 31 * result + (implementationFactory?.hashCode() ?: 0)
        result = 31 * result + (implementationType?.hashCode() ?: 0)
        result = 31 * result + (instance?.hashCode() ?: 0)
        result = 31 * result + lifetime.hashCode()
        result = 31 * result + (key?.hashCode() ?: 0)
        result = 31 * result + serviceId.hashCode()
        result = 31 * result + implementationId.hashCode()
        return result
    }

    companion object {
        fun serviceId(serviceType: Class<*>, key: String? = null): String {
            return if (key == null) serviceType.name else serviceType.name + "@" + key
        }

        fun <T: Any> createWithInstance(
            serviceType: Class<T>,
            instance: T? = null,
            lifetime: ServiceLifetimes = Singleton,
            key: String? = null,
        ) = ServiceDescriptor(
            serviceType = serviceType,
            instance = instance,
            lifetime = lifetime,
            key = key
        )

        fun <T: Any> createWithImplementationFactory(
            serviceType: Class<T>,
            implementationFactory: ImplementationFactory<T>? = null,
            lifetime: ServiceLifetimes = Singleton,
            key: String? = null,
        ) = ServiceDescriptor(
            serviceType = serviceType,
            implementationFactory = implementationFactory,
            lifetime = lifetime,
            key = key
        )

        fun <T: Any, TImplementation: T> createWithImplementationType(
            serviceType: Class<T>,
            implementationType: Class<TImplementation>? = null,
            lifetime: ServiceLifetimes = Singleton,
            key: String? = null,
        ) = ServiceDescriptor(
            serviceType = serviceType,
            implementationType = implementationType,
            lifetime = lifetime,
            key = key
        )
    }
}

typealias ImplementationFactory<T> = (services: ServiceProvider) -> T
