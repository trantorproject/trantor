package dev.botta.trantor.serviceProvider

import dev.botta.trantor.serviceProvider.ServiceLifetimes.*

data class ServiceDescriptor<T: Any>(
    val serviceType: Class<T>,
    val implementationFactory: ImplementationFactory<T>,
    val lifetime: ServiceLifetimes = Singleton,
    val key: String? = null,
) {
    val serviceId = serviceId(serviceType, key)

    companion object {
        fun serviceId(serviceType: Class<*>, key: String? = null): String {
            return if (key === null) serviceType.name else serviceType.name + "." + key
        }

        fun <T: Any> singleton(
            serviceType: Class<T>,
            implementationFactory: ImplementationFactory<T>,
            key: String? = null,
        ): ServiceDescriptor<T> {
            return ServiceDescriptor(serviceType, implementationFactory, Singleton, key)
        }

        inline fun <reified T: Any> singleton(
            noinline implementationFactory: ImplementationFactory<T>,
            key: String? = null,
        ): ServiceDescriptor<T> {
            return singleton(T::class.java, implementationFactory, key)
        }

        fun <T: Any> scoped(
            serviceType: Class<T>,
            implementationFactory: ImplementationFactory<T>,
            key: String? = null,
        ): ServiceDescriptor<T> {
            return ServiceDescriptor(serviceType, implementationFactory, Scoped, key)
        }

        inline fun <reified T: Any> scoped(
            noinline implementationFactory: ImplementationFactory<T>,
            key: String? = null,
        ): ServiceDescriptor<T> {
            return scoped(T::class.java, implementationFactory, key)
        }
    }
}

typealias ImplementationFactory<T> = (services: ServiceProvider) -> T
