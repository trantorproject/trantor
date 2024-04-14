package dev.botta.trantor.tx

interface Transaction: AutoCloseable {
    val isClosed: Boolean
    fun commit()
    fun rollback()
}
