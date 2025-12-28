package dev.botta.trantor.tx

class NullTransactionManager: TransactionManager {
    override fun beginTransaction(): Transaction {
        return NullTransaction()
    }
}
