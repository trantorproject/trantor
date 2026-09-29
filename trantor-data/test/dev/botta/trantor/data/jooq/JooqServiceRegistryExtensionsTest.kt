package dev.botta.trantor.data.jooq

import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.config.providers.addMemoryCollection
import dev.botta.trantor.data.jdbc.transactions.TransactionAwareDataSource
import dev.botta.trantor.di.DefaultServiceProvider
import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.serialization.gson.GsonSerializer
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import javax.sql.DataSource

class JooqServiceRegistryExtensionsTest {
    @Test
    fun `does not lock optimistically unless the configuration says so`() {
        registry.addJooq()

        assertThat(dsl().settings().isExecuteWithOptimisticLocking).isFalse
    }

    @Test
    fun `locks optimistically the records with a version field when the configuration says so`() {
        config.addMemoryCollection("jooq.optimisticLocking" to "true")

        registry.addJooq()

        assertThat(dsl().settings().isExecuteWithOptimisticLocking).isTrue
        assertThat(dsl().settings().isExecuteWithOptimisticLockingExcludeUnversioned).isTrue
    }

    @Test
    fun `translates the errors of the database unless the configuration says not to`() {
        registry.addJooq()

        assertThat(listeners()).hasExactlyElementsOfTypes(JooqErrorTranslator::class.java)
    }

    @Test
    fun `leaves the errors of the database as jOOQ throws them when the configuration says so`() {
        config.addMemoryCollection("jooq.translateErrors" to "false")

        registry.addJooq()

        assertThat(listeners()).isEmpty()
    }

    @Test
    fun `logs the SQL besides translating the errors`() {
        config.addMemoryCollection("jooq.logSql" to "true")

        registry.addJooq()

        assertThat(listeners()).hasExactlyElementsOfTypes(JooqErrorTranslator::class.java, SQLLogger::class.java)
    }

    private fun listeners() = dsl().configuration().executeListenerProviders().map { it.provide() }

    private fun dsl() = DefaultServiceProvider(registry).get<DSLContext>()

    private val config = ConfigManager()
    private val registry = ServiceRegistry(config).apply {
        addSingleton<JsonSerializer>(GsonSerializer())
        addSingleton<DataSource>(TransactionAwareDataSource(mockk()))
    }
}
