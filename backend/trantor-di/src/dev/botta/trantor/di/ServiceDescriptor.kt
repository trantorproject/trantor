package dev.botta.trantor.di

import dev.botta.trantor.di.ServiceLifetimes.Singleton

data class ServiceDescriptor<T: Any, TImplementation: T>(
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

    companion object {
        fun serviceId(serviceType: Class<*>, key: String? = null): String {
            return if (key == null) serviceType.name else serviceType.name + "@" + key
        }
    }
}

typealias ImplementationFactory<T> = (services: ServiceProvider) -> T
