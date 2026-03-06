package dev.botta.trantor.data.jdbc.transactions.cqbus

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.core.tx.*

class TransactionalMiddleware(private val transactionManager: TransactionManager): Middleware {
    override fun <T: Request<R>, R> execute(request: T, next: (T) -> R, context: ExecutionContext): R {
        return transactionManager.transactional {
            next(request)
        }
    }
}
