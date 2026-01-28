package dev.botta.trantor.core.tx

class NullTransactionManager: TransactionManager {
    override val activeTransaction: Transaction? = null

    override fun beginTransaction(): Transaction {
        return NullTransaction()
    }
}
