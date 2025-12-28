package dev.botta.trantor.data.jooq

import dev.botta.trantor.data.jdbc.*
import dev.botta.trantor.serviceProvider.ServiceRegistry
import dev.botta.trantor.tx.TransactionManager
import org.jooq.*
import org.jooq.impl.*
import org.jooq.tools.jdbc.JDBCUtils
import javax.sql.DataSource

fun ServiceRegistry.addJooq(key: String? = null) = apply {
    if (has<DSLContext>(key)) return@apply

    addJdbc(key)

    addConfig<JooqConfig>("jooqConfig")

    addSingleton<DSLContext>(key) {
        val jdbcConfig = it.tryGet<JdbcConfig>(key)
        val jooqConfig = it.getOrDefault<JooqConfig>(key) { JooqConfig() }
        val jooqConfiguration = DefaultConfiguration()
        System.getProperties().setProperty("org.jooq.no-logo", "true")
        System.getProperties().setProperty("org.jooq.no-tips", "true")
        jooqConfiguration.setDataSource(it.get<DataSource>(key))
        if (jooqConfig.dialect != null) {
            jooqConfiguration.setSQLDialect(SQLDialect.valueOf(jooqConfig.dialect!!.uppercase()))
        } else if (jdbcConfig != null) {
            jooqConfiguration.setSQLDialect(JDBCUtils.dialect(jdbcConfig.url))
        }
        if (jooqConfig.logSql) {
            jooqConfiguration.set(DefaultExecuteListenerProvider(SQLLogger()))
        }
        val transactionManager = it.get<TransactionManager>(key)
        jooqConfiguration.set(TrantorJooqTransactionProvider(transactionManager))

        jooqConfiguration.dsl()
    }
}
