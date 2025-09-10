package dev.botta.trantor.appServices

import dev.botta.cqbus.MiddlewarePriorities.*
import dev.botta.trantor.appServices.middlewares.*
import dev.botta.trantor.eventBus.InProcessEventBus
import dev.botta.trantor.eventBus.cqbus.InProcessEventBusMiddleware
import dev.botta.trantor.serviceProvider.ServiceProvider
import dev.botta.trantor.tx.*

abstract class DefaultAppModule(services: ServiceProvider): AppModule(services) {
    protected val transactionManager: TransactionManager = services.getOrDefault { NullTransactionManager() }

    init {
        if (eventBus is InProcessEventBus) registerMiddleware(InProcessEventBusMiddleware(eventBus), Low)
        registerMiddleware(TransactionalMiddleware(transactionManager), Normal)
        registerMiddleware(LoggingMiddleware(), VeryHigh)
    }
}
