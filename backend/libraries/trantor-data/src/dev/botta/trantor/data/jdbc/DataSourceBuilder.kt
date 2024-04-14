package dev.botta.trantor.data.jdbc

import dev.botta.lang.DetailsExt
import dev.botta.trantor.data.jdbc.connectionFactory.*
import dev.botta.trantor.data.jdbc.connectionFactory.credentials.*
import dev.botta.trantor.data.jdbc.transactions.manager.*

class DataSourceBuilder {
    private var credentials = JdbcCredentials(JdbcUrl("", "", 0, ""), "", "")
    private var createConnectionFactory: () -> JdbcConnectionFactory = { HikariCPConnectionFactory(credentials) }
    private var createTransactionManagerFactory: (DataSource) -> JdbcTransactionManager = { ThreadLocalJdbcTransactionManager(it) }

    fun dbCredentials(credentials: JdbcCredentials) = credentials.also { this.credentials = it }

    fun dbCredentialsFromEnv(prefix: String = "DB"): JdbcCredentials {
        return JdbcCredentialsFromEnvironmentFactory().create(prefix).also { this.credentials = it }
    }

    fun simpleDbConnections() { createConnectionFactory = { SimpleJdbcConnectionFactory(credentials) } }

    fun pooledDbConnections(maxPoolSize: Int = 10) {
        this.createConnectionFactory = { HikariCPConnectionFactory(credentials, maxPoolSize) }
    }

    fun simpleTransactions() = apply { createTransactionManagerFactory = { SimpleJdbcTransactionManager(it) } }

    fun threadLocalTransactions() = apply { createTransactionManagerFactory = { ThreadLocalJdbcTransactionManager(it) } }

    fun build() = DataSource(
        createConnectionFactory(),
        credentials,
        createTransactionManagerFactory
    )
}

fun dataSource(addDetails: DetailsExt<DataSourceBuilder>) = DataSourceBuilder().apply(addDetails).build()
