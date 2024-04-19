package dev.botta.trantor.appServices.middlewares

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.tx.*

class TransactionalMiddleware(private val transactionManager: TransactionManager): Middleware {
    override fun <T: Request<R>, R> execute(request: T, next: (T) -> R, context: ExecutionContext): R {
        return transactionManager.transactional { next(request) }
    }
}
