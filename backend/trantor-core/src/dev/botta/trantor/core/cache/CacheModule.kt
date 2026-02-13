package dev.botta.trantor.core.cache

import dev.botta.trantor.config.*
import dev.botta.trantor.di.*
import dev.botta.trantor.hosting.Module

class CacheModule: Module {
    override fun compose(services: ServiceRegistry, config: ConfigManager) {
        services.addSingletonIfMissing<InMemoryCacheFactory, DefaultInMemoryCacheFactory>()
    }

    override fun initialize(services: ServiceProvider, config: Config) {
    }
}
