package dev.botta.trantor.data.coroutines

import dev.botta.trantor.serviceProvider.ServiceRegistry

fun ServiceRegistry.addDbDispatcherProvider(key: String? = null) = apply {
    if (has<DbDispatcherProvider>(key)) return@apply

    addSingletonIfMissing<DbDispatcherProvider, DefaultDbDispatcherProvider>(key)
}
