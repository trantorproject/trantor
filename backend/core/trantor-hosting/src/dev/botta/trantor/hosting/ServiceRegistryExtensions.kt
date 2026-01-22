package dev.botta.trantor.hosting

import dev.botta.trantor.di.*

fun ServiceRegistry.addHostedService(factory: ImplementationFactory<HostedService>) {
    addSingleton<HostedService>(factory)
}

fun ServiceRegistry.addHostedService(key: String? = null, factory: ImplementationFactory<HostedService>) {
    addSingleton<HostedService>(key, factory)
}

fun <TImplementation: HostedService> ServiceRegistry.addHostedService(implementationType: Class<TImplementation>, key: String? = null) {
    addSingleton(HostedService::class.java, implementationType, key)
}

inline fun <reified TImplementation: HostedService> ServiceRegistry.addHostedService(key: String? = null) {
    addSingleton(HostedService::class.java, TImplementation::class.java, key)
}

fun <TImplementation: HostedService> ServiceRegistry.addHostedService(implementation: TImplementation, key: String? = null) {
    addSingleton<HostedService>(implementation, key)
}
