package dev.botta.trantor.data.jooq

import dev.botta.trantor.core.tx.TransactionManager
import dev.botta.trantor.data.jdbc.*
import dev.botta.trantor.di.ServiceRegistry
import org.jooq.*
import org.jooq.conf.Settings
import org.jooq.impl.*
import org.jooq.tools.jdbc.JDBCUtils
import javax.sql.DataSource

fun ServiceRegistry.addJooq(key: String? = null) = apply {
    if (has<DSLContext>(key)) return@apply

    addJdbc(key)

    addConfig<JooqSettings>("jooq")

    addSingleton<DSLContext>(key) {
        val jdbcSettings = it.tryGet<JdbcSettings>(key)
        val jooqSettings = it.getOrDefault<JooqSettings>(key) { JooqSettings() }
        val jooqConfiguration = DefaultConfiguration()
        System.getProperties().setProperty("org.jooq.no-logo", "true")
        System.getProperties().setProperty("org.jooq.no-tips", "true")
        jooqConfiguration.setDataSource(it.get<DataSource>(key))
        if (jooqSettings.dialect != null) {
            jooqConfiguration.setSQLDialect(SQLDialect.valueOf(jooqSettings.dialect!!.uppercase()))
        } else if (jdbcSettings != null) {
            jooqConfiguration.setSQLDialect(JDBCUtils.dialect(jdbcSettings.url))
        }
        val listeners = buildList {
            if (jooqSettings.translateErrors) add(JooqErrorTranslator())
            if (jooqSettings.logSql) add(SQLLogger())
        }
        jooqConfiguration.set(*DefaultExecuteListenerProvider.providers(*listeners.toTypedArray()))
        if (jooqSettings.optimisticLocking) {
            jooqConfiguration.set(
                Settings().withExecuteWithOptimisticLocking(true).withExecuteWithOptimisticLockingExcludeUnversioned(true)
            )
        }
        val transactionManager = it.get<TransactionManager>(key)
        jooqConfiguration.set(TrantorJooqTransactionProvider(transactionManager))

        jooqConfiguration.dsl()
    }
}
