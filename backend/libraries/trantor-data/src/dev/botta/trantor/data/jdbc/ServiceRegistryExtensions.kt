package dev.botta.trantor.data.jdbc

import com.zaxxer.hikari.*
import dev.botta.trantor.config.addConfig
import dev.botta.trantor.data.jdbc.transactions.TransactionAwareDataSource
import dev.botta.trantor.data.jdbc.transactions.manager.ThreadLocalJdbcTransactionManager
import dev.botta.trantor.serviceProvider.*
import dev.botta.trantor.tx.TransactionManager
import javax.sql.DataSource

fun ServiceRegistry.addJdbcConfig(key: String? = null) = apply {
    if (has<JdbcConfig>(key)) return@apply
    addConfig<JdbcConfig>(if (key != null) "jdbc.${key}" else "jdbc", key)
}

fun ServiceRegistry.addHikariCP(key: String? = null, config: ServiceConfiguration<HikariConfig> = {}) = apply {
    addJdbcConfig(key)

    addSingleton<DataSource>(key) {
        val hikariConfig = HikariConfig()
        hikariConfig.maximumPoolSize = 10
        config(hikariConfig)
        val jdbcConfig = it.get<JdbcConfig>(key)
        hikariConfig.jdbcUrl = jdbcConfig.url
        hikariConfig.username = jdbcConfig.username
        hikariConfig.password = jdbcConfig.password
        TransactionAwareDataSource(HikariDataSource(hikariConfig))
    }
}

fun ServiceRegistry.addHikariCP(config: ServiceConfiguration<HikariConfig> = {}) = apply {
    addHikariCP(null, config)
}

fun ServiceRegistry.addSimpleDataSource(key: String? = null) = apply {
    addJdbcConfig(key)

    addSingleton<DataSource>(key) {
        val jdbcConfig = it.get<JdbcConfig>(key)
        TransactionAwareDataSource(SimpleDataSource(jdbcConfig))
    }
}

fun ServiceRegistry.addJdbcTransactionManager(key: String? = null) = apply {
    if (!has<DataSource>(key)) addHikariCP(key)

    addSingleton<TransactionManager>(key) {
        val dataSource = it.get<DataSource>()
        if (dataSource !is TransactionAwareDataSource) throw Exception("DataSource must implement TransactionAwareDataSource to use transactions")
        ThreadLocalJdbcTransactionManager(dataSource)
    }
}

fun ServiceRegistry.addSimpleJdbcTransactionManager(key: String? = null) = apply {
    if (!has<DataSource>(key)) addSimpleDataSource(key)

    addSingleton<TransactionManager>(key) {
        val dataSource = it.get<DataSource>()
        if (dataSource !is TransactionAwareDataSource) throw Exception("DataSource must implement TransactionAwareDataSource to use transactions")
        ThreadLocalJdbcTransactionManager(dataSource)
    }
}

fun ServiceRegistry.addJdbc(key: String? = null) = apply {
    if (!has<JdbcConfig>(key)) addJdbcConfig(key)
    if (!has<DataSource>(key)) addHikariCP(key)
    if (!has<TransactionManager>(key)) addJdbcTransactionManager(key)
}
