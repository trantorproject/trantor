package dev.botta.trantor.serviceProvider

import dev.botta.trantor.serviceProvider.ServiceLifetimes.Singleton

data class ServiceDescriptor<T>(
    val serviceType: Class<T>,
    val implementationFactory: ImplementationFactory<T>,
    val lifetime: ServiceLifetimes = Singleton,
    val key: String? = null,
)

typealias ImplementationFactory<T> = (services: ServiceProvider) -> T
