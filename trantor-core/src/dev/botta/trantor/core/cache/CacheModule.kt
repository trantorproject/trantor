package dev.botta.trantor.core.cache

import dev.botta.trantor.config.*
import dev.botta.trantor.core.tx.TransactionsModule
import dev.botta.trantor.di.*
import dev.botta.trantor.hosting.Module
import dev.botta.trantor.hosting.addModule

class CacheModule: Module {
    override fun compose(services: ServiceRegistry, config: ConfigManager) {
        // A cache keeps a level per transaction, so the factory needs a TransactionManager. addModule is
        // idempotent, so bringing it in here costs nothing to an application that already composed it
        services.addModule<TransactionsModule>()

        services.addSingletonIfMissing<InMemoryCacheFactory, DefaultInMemoryCacheFactory>()
    }

    override fun initialize(services: ServiceProvider, config: Config) {
    }
}
