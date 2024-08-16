package dev.botta.trantor.data.jooq

import dev.botta.trantor.tx.TransactionManager
import org.jooq.*

class TrantorJooqTransactionProvider(private val transactionManager: TransactionManager): TransactionProvider {
    override fun begin(ctx: TransactionContext) {
        val transaction = transactionManager.beginTransaction()
        ctx.transaction(TrantorJooqTransaction(transaction))
    }

    override fun commit(ctx: TransactionContext) {
        (ctx.transaction() as? TrantorJooqTransaction)?.inner?.commit()
    }

    override fun rollback(ctx: TransactionContext) {
        (ctx.transaction() as? TrantorJooqTransaction)?.inner?.rollback()
    }
}
