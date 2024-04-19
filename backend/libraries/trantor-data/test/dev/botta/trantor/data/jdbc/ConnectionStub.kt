package dev.botta.trantor.data.jdbc

import java.sql.*
import java.util.*
import java.util.concurrent.Executor

class ConnectionStub(private val savePoints: SavePoints) : Connection {
    var wasCommited = false
        private set
    var wasRollbacked = false
        private set
    private var closed = false
    private var autoCommitValue = true
    private var savepointNextId = 1

    override fun setAutoCommit(p0: Boolean) {
        autoCommitValue = p0
    }

    override fun getAutoCommit(): Boolean {
        return autoCommitValue
    }

    override fun commit() {
        wasCommited = true
    }

    override fun rollback() {
        wasRollbacked = true
    }

    override fun rollback(p0: Savepoint?) {
        if (p0 != null) {
            savePoints.active.remove(p0)
            savePoints.rollbacked.add(p0)
        }
        wasRollbacked = true
    }

    override fun isClosed(): Boolean {
        return closed
    }

    override fun close() {
        closed = true
    }

    override fun setSavepoint(): Savepoint {
        val savepoint = SavepointStub(savepointNextId++)
        savePoints.active.add(savepoint)
        return savepoint
    }

    override fun setSavepoint(p0: String?): Savepoint {
        val savepoint = SavepointStub(savepointNextId++, p0)
        savePoints.active.add(savepoint)
        return savepoint
    }

    override fun releaseSavepoint(p0: Savepoint?) {
        if (p0 != null) {
            savePoints.active.remove(p0)
            savePoints.released.add(p0)
        }
    }

    override fun <T: Any?> unwrap(p0: Class<T>?): T {
        throw Throwable("Not Implemented")
    }

    override fun isWrapperFor(p0: Class<*>?): Boolean {
        throw Throwable("Not Implemented")
    }

    override fun createStatement(): Statement {
        throw Throwable("Not Implemented")
    }

    override fun createStatement(p0: Int, p1: Int): Statement {
        throw Throwable("Not Implemented")
    }

    override fun createStatement(p0: Int, p1: Int, p2: Int): Statement {
        throw Throwable("Not Implemented")
    }

    override fun prepareStatement(p0: String?): PreparedStatement {
        throw Throwable("Not Implemented")
    }

    override fun prepareStatement(p0: String?, p1: Int, p2: Int): PreparedStatement {
        throw Throwable("Not Implemented")
    }

    override fun prepareStatement(p0: String?, p1: Int, p2: Int, p3: Int): PreparedStatement {
        throw Throwable("Not Implemented")
    }

    override fun prepareStatement(p0: String?, p1: Int): PreparedStatement {
        throw Throwable("Not Implemented")
    }

    override fun prepareStatement(p0: String?, p1: IntArray?): PreparedStatement {
        throw Throwable("Not Implemented")
    }

    override fun prepareStatement(p0: String?, p1: Array<out String>?): PreparedStatement {
        throw Throwable("Not Implemented")
    }

    override fun prepareCall(p0: String?): CallableStatement {
        throw Throwable("Not Implemented")
    }

    override fun prepareCall(p0: String?, p1: Int, p2: Int): CallableStatement {
        throw Throwable("Not Implemented")
    }

    override fun prepareCall(p0: String?, p1: Int, p2: Int, p3: Int): CallableStatement {
        throw Throwable("Not Implemented")
    }

    override fun nativeSQL(p0: String?): String {
        throw Throwable("Not Implemented")
    }

    override fun getMetaData(): DatabaseMetaData {
        throw Throwable("Not Implemented")
    }

    override fun setReadOnly(p0: Boolean) {
        throw Throwable("Not Implemented")
    }

    override fun isReadOnly(): Boolean {
        throw Throwable("Not Implemented")
    }

    override fun setCatalog(p0: String?) {
        throw Throwable("Not Implemented")
    }

    override fun getCatalog(): String {
        throw Throwable("Not Implemented")
    }

    override fun setTransactionIsolation(p0: Int) {
        throw Throwable("Not Implemented")
    }

    override fun getTransactionIsolation(): Int {
        throw Throwable("Not Implemented")
    }

    override fun getWarnings(): SQLWarning {
        throw Throwable("Not Implemented")
    }

    override fun clearWarnings() {
        throw Throwable("Not Implemented")
    }

    override fun getTypeMap(): MutableMap<String, Class<*>> {
        throw Throwable("Not Implemented")
    }

    override fun setTypeMap(p0: MutableMap<String, Class<*>>?) {
        throw Throwable("Not Implemented")
    }

    override fun setHoldability(p0: Int) {
        throw Throwable("Not Implemented")
    }

    override fun getHoldability(): Int {
        throw Throwable("Not Implemented")
    }

    override fun createClob(): Clob {
        throw Throwable("Not Implemented")
    }

    override fun createBlob(): Blob {
        throw Throwable("Not Implemented")
    }

    override fun createNClob(): NClob {
        throw Throwable("Not Implemented")
    }

    override fun createSQLXML(): SQLXML {
        throw Throwable("Not Implemented")
    }

    override fun isValid(p0: Int): Boolean {
        throw Throwable("Not Implemented")
    }

    override fun setClientInfo(p0: String?, p1: String?) {
        throw Throwable("Not Implemented")
    }

    override fun setClientInfo(p0: Properties?) {
        throw Throwable("Not Implemented")
    }

    override fun getClientInfo(p0: String?): String {
        throw Throwable("Not Implemented")
    }

    override fun getClientInfo(): Properties {
        throw Throwable("Not Implemented")
    }

    override fun createArrayOf(p0: String?, p1: Array<out Any>?): java.sql.Array {
        throw Throwable("Not Implemented")
    }

    override fun createStruct(p0: String?, p1: Array<out Any>?): Struct {
        throw Throwable("Not Implemented")
    }

    override fun setSchema(p0: String?) {
        throw Throwable("Not Implemented")
    }

    override fun getSchema(): String {
        throw Throwable("Not Implemented")
    }

    override fun abort(p0: Executor?) {
        throw Throwable("Not Implemented")
    }

    override fun setNetworkTimeout(p0: Executor?, p1: Int) {
        throw Throwable("Not Implemented")
    }

    override fun getNetworkTimeout(): Int {
        throw Throwable("Not Implemented")
    }
}
