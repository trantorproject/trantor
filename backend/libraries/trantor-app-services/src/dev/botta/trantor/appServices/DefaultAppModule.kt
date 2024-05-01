package dev.botta.trantor.appServices

import dev.botta.cqbus.MiddlewarePriorities.*
import dev.botta.trantor.appServices.middlewares.*
import dev.botta.trantor.eventBus.*
import dev.botta.trantor.tx.*

abstract class DefaultAppModule(
    transactionManager: TransactionManager = NullTransactionManager(),
): AppModule(InProcessEventBus()) {
    init {
        registerMiddleware(InProcessEventBusMiddleware(eventBus as InProcessEventBus), Low)
        registerMiddleware(TransactionalMiddleware(transactionManager), Normal)
        registerMiddleware(AuthorizationMiddleware(), Normal)
        registerMiddleware(LoggingMiddleware(), VeryHigh)
    }
}
