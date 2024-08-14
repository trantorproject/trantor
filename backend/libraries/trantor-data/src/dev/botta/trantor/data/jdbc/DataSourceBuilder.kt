package dev.botta.trantor.data.jdbc

import dev.botta.lang.DetailsExt
import dev.botta.trantor.data.jdbc.credentials.*
import dev.botta.trantor.data.jdbc.transactions.TransactionAwareDataSource
import dev.botta.trantor.data.jdbc.transactions.manager.*

class DataSourceBuilder {
    private var credentials = JdbcCredentials(JdbcUrl("", "", 0, ""), "", "")
    private var createDataSourceFactory: () -> TransactionAwareDataSource = {
        TransactionAwareDataSource(HikariDataSourceFactory().create(credentials))
    }
    private var createTransactionManagerFactory: (TransactionAwareDataSource) -> JdbcTransactionManager = { ThreadLocalJdbcTransactionManager(it) }

    fun dbCredentials(credentials: JdbcCredentials) = credentials.also { this.credentials = it }

    fun dbCredentialsFromEnv(prefix: String = "DB"): JdbcCredentials {
        return JdbcCredentialsFromEnvironmentFactory().create(prefix).also { this.credentials = it }
    }

//    fun simpleDbConnections() { createConnectionFactory = { SimpleJdbcConnectionFactory(credentials) } }

    fun pooled(maxPoolSize: Int = 10) {
        this.createDataSourceFactory = { TransactionAwareDataSource(HikariDataSourceFactory().create(credentials, maxPoolSize)) }
    }

    fun simpleTransactions() = apply { createTransactionManagerFactory = { SimpleJdbcTransactionManager(it) } }

    fun threadLocalTransactions() = apply { createTransactionManagerFactory = { ThreadLocalJdbcTransactionManager(it) } }

    fun build() = createDataSourceFactory()
}

fun dataSource(addDetails: DetailsExt<DataSourceBuilder>) = DataSourceBuilder().apply(addDetails).build()
