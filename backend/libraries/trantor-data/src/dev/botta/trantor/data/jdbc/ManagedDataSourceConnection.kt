package dev.botta.trantor.data.jdbc

import java.sql.*
import java.util.*
import java.util.concurrent.Executor

class ManagedDataSourceConnection(private val datasource: DataSource, private val connection: Connection): Connection {
    override fun close() {
        datasource.release(connection)
    }

    override fun <T: Any?> unwrap(p0: Class<T>?): T {
        return connection.unwrap(p0)
    }

    override fun isWrapperFor(p0: Class<*>?): Boolean {
        return connection.isWrapperFor(p0)
    }

    override fun createStatement(): Statement {
        return connection.createStatement()
    }

    override fun createStatement(p0: Int, p1: Int): Statement {
        return connection.createStatement(p0, p1)
    }

    override fun createStatement(p0: Int, p1: Int, p2: Int): Statement {
        return connection.createStatement(p0, p1, p2)
    }

    override fun prepareStatement(p0: String?): PreparedStatement {
        return connection.prepareStatement(p0)
    }

    override fun prepareStatement(p0: String?, p1: Int, p2: Int): PreparedStatement {
        return connection.prepareStatement(p0, p1, p2)
    }

    override fun prepareStatement(p0: String?, p1: Int, p2: Int, p3: Int): PreparedStatement {
        return connection.prepareStatement(p0, p1, p2, p3)
    }

    override fun prepareStatement(p0: String?, p1: Int): PreparedStatement {
        return connection.prepareStatement(p0, p1)
    }

    override fun prepareStatement(p0: String?, p1: IntArray?): PreparedStatement {
        return connection.prepareStatement(p0, p1)
    }

    override fun prepareStatement(p0: String?, p1: Array<out String>?): PreparedStatement {
        return connection.prepareStatement(p0, p1)
    }

    override fun prepareCall(p0: String?): CallableStatement {
        return connection.prepareCall(p0)
    }

    override fun prepareCall(p0: String?, p1: Int, p2: Int): CallableStatement {
        return connection.prepareCall(p0, p1, p2)
    }

    override fun prepareCall(p0: String?, p1: Int, p2: Int, p3: Int): CallableStatement {
        return connection.prepareCall(p0, p1, p2, p3)
    }

    override fun nativeSQL(p0: String?): String {
        return connection.nativeSQL(p0)
    }

    override fun setAutoCommit(p0: Boolean) {
        connection.autoCommit = p0
    }

    override fun getAutoCommit(): Boolean {
        return connection.autoCommit
    }

    override fun commit() {
        connection.commit()
    }

    override fun rollback() {
        connection.rollback()
    }

    override fun rollback(p0: Savepoint?) {
        connection.rollback(p0)
    }

    override fun isClosed(): Boolean {
        return connection.isClosed
    }

    override fun getMetaData(): DatabaseMetaData {
        return connection.metaData
    }

    override fun setReadOnly(p0: Boolean) {
        connection.isReadOnly = p0
    }

    override fun isReadOnly(): Boolean {
        return connection.isReadOnly
    }

    override fun setCatalog(p0: String?) {
        connection.catalog = p0
    }

    override fun getCatalog(): String {
        return connection.catalog
    }

    override fun setTransactionIsolation(p0: Int) {
        connection.transactionIsolation = p0
    }

    override fun getTransactionIsolation(): Int {
        return connection.transactionIsolation
    }

    override fun getWarnings(): SQLWarning {
        return connection.warnings
    }

    override fun clearWarnings() {
        connection.clearWarnings()
    }

    override fun getTypeMap(): MutableMap<String, Class<*>> {
        return connection.typeMap
    }

    override fun setTypeMap(p0: MutableMap<String, Class<*>>?) {
        connection.typeMap = p0
    }

    override fun setHoldability(p0: Int) {
        connection.holdability = p0
    }

    override fun getHoldability(): Int {
        return connection.holdability
    }

    override fun setSavepoint(): Savepoint {
        return connection.setSavepoint()
    }

    override fun setSavepoint(p0: String?): Savepoint {
        return connection.setSavepoint(p0)
    }

    override fun releaseSavepoint(p0: Savepoint?) {
        connection.releaseSavepoint(p0)
    }

    override fun createClob(): Clob {
        return connection.createClob()
    }

    override fun createBlob(): Blob {
        return connection.createBlob()
    }

    override fun createNClob(): NClob {
        return connection.createNClob()
    }

    override fun createSQLXML(): SQLXML {
        return connection.createSQLXML()
    }

    override fun isValid(p0: Int): Boolean {
        return connection.isValid(p0)
    }

    override fun setClientInfo(p0: String?, p1: String?) {
        connection.setClientInfo(p0, p1)
    }

    override fun setClientInfo(p0: Properties?) {
        connection.clientInfo = p0
    }

    override fun getClientInfo(p0: String?): String {
        return connection.getClientInfo(p0)
    }

    override fun getClientInfo(): Properties {
        return connection.clientInfo
    }

    override fun createArrayOf(p0: String?, p1: Array<out Any>?): java.sql.Array {
        return connection.createArrayOf(p0, p1)
    }

    override fun createStruct(p0: String?, p1: Array<out Any>?): Struct {
        return connection.createStruct(p0, p1)
    }

    override fun setSchema(p0: String?) {
        connection.schema = p0
    }

    override fun getSchema(): String {
        return connection.schema
    }

    override fun abort(p0: Executor?) {
        connection.abort(p0)
    }

    override fun setNetworkTimeout(p0: Executor?, p1: Int) {
        connection.setNetworkTimeout(p0, p1)
    }

    override fun getNetworkTimeout(): Int {
        return connection.networkTimeout
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is ManagedDataSourceConnection) {
            return connection == other.connection
        }
        return connection == other
    }

    override fun hashCode(): Int {
        return connection.hashCode()
    }
}
