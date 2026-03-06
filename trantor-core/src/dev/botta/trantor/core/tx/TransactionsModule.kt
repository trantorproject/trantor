package dev.botta.trantor.core.tx

import dev.botta.trantor.config.*
import dev.botta.trantor.di.*
import dev.botta.trantor.hosting.Module

class TransactionsModule: Module {
    override fun compose(services: ServiceRegistry, config: ConfigManager) {
        services.addSingletonIfMissing<TransactionManager, NullTransactionManager>()
    }

    override fun initialize(services: ServiceProvider, config: Config) {
    }
}
