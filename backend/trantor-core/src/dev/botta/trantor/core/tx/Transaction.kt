package dev.botta.trantor.core.tx

interface Transaction: AutoCloseable {
    val isClosed: Boolean
    fun commit()
    fun rollback()
}
