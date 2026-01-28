package dev.botta.trantor.core.tx

interface Transaction: AutoCloseable {
    val isClosed: Boolean
    fun commit()
    fun rollback()
    fun afterCommit(action: () -> Unit)
    fun afterRollback(action: () -> Unit)
}
