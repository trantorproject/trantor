package dev.botta.trantor.data.jdbc.transactions.cqbus

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.tx.*

class TransactionalMiddleware(private val transactionManager: TransactionManager): Middleware {
    override suspend fun <T: Request<R>, R> execute(request: T, next: suspend (T) -> R, context: ExecutionContext): R {
        return transactionManager.transactional {
            next(request)
        }
    }
}
