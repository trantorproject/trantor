package dev.botta.trantor.data.jdbc.transactions

import dev.botta.trantor.core.tx.Transaction
import java.sql.*
import java.util.*

class JdbcTransaction(val connection: Connection, private val onClose: () -> Unit): Transaction {
    private val afterCommitActions = mutableListOf<() -> Unit>()
    private val afterRollbackActions = mutableListOf<() -> Unit>()
    private var savepoints = ArrayDeque<Savepoint>()
    override var isClosed = false
        private set

    init {
        connection.autoCommit = false
    }

    fun beginNested() {
        val savepoint = connection.setSavepoint()
        savepoints.push(savepoint)
    }

    override fun commit() {
        if (isClosed) return

        if (hasNested()) {
            commitNested()
            return
        }

        try {
            connection.commit()
            afterCommitActions.forEach { it() }
        } finally {
            connection.autoCommit = true
            setClosed()
        }
    }

    private fun commitNested() {
        val savepoint = savepoints.pop()
        connection.releaseSavepoint(savepoint)
    }

    private fun hasNested() = savepoints.isNotEmpty()

    override fun rollback() {
        if (isClosed) return

        if (hasNested()) {
            rollbackNested()
            return
        }
        try {
            connection.rollback()
            afterRollbackActions.forEach { it() }
        } finally {
            connection.autoCommit = true
            setClosed()
        }
    }

    override fun afterComplete(action: () -> Unit) {
        afterCommit(action)
        afterRollback(action)
    }

    override fun afterCommit(action: () -> Unit) {
        if (isClosed) error("Cannot add callback to a closed transaction")
        afterCommitActions.add(action)
    }

    override fun afterRollback(action: () -> Unit) {
        if (isClosed) error("Cannot add callback to a closed transaction")
        afterRollbackActions.add(action)
    }

    private fun rollbackNested() {
        val savepoint = savepoints.pop()
        connection.rollback(savepoint)
    }

    private fun setClosed() {
        isClosed = true
        onClose()
    }

    override fun close() {
        if (isClosed) return
        rollback()
    }
}
