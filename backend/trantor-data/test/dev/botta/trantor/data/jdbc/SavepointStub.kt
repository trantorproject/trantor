package dev.botta.trantor.data.jdbc

import java.sql.Savepoint

class SavepointStub(private val id: Int, private val name: String? = null): Savepoint {
    override fun getSavepointId() = id

    override fun getSavepointName() = name ?: "Savepoint $id"
}
