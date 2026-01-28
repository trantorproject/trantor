package dev.botta.trantor.core.tx

class NullTransaction: Transaction {
    override var isClosed: Boolean = false

    override fun commit() {
    }

    override fun rollback() {
    }

    override fun afterCommit(action: () -> Unit) {
    }

    override fun afterRollback(action: () -> Unit) {
    }

    override fun close() {
        isClosed = true
    }
}
