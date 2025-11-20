package dev.botta.trantor.tx

import kotlin.coroutines.CoroutineContext

class NullTransactionManager: TransactionManager {
    override fun beginTransaction(): Transaction {
        return NullTransaction()
    }

    override val transactionContext: CoroutineContext.Element
        get() = NullTxContextElement

    private object NullTxContextElement : CoroutineContext.Element {
        override val key: CoroutineContext.Key<*> = Key
        private object Key : CoroutineContext.Key<NullTxContextElement>
    }
}
