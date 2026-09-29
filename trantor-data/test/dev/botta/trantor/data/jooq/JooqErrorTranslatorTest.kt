@file:Suppress("ClassName")

package dev.botta.trantor.data.jooq

import dev.botta.trantor.data.errors.UniqueViolationError
import dev.botta.trantor.data.testing.RecordedErrors.MariaDb
import dev.botta.trantor.data.testing.RecordedErrors.MySql
import dev.botta.trantor.data.testing.RecordedErrors.Postgres
import org.assertj.core.api.Assertions.assertThat
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.jooq.impl.DefaultExecuteListenerProvider
import org.jooq.tools.jdbc.MockConnection
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException

class JooqErrorTranslatorTest {
    @Nested
    inner class `on Postgres` {
        @Test
        fun `names the constraint from the fields the server sent`() {
            val error = assertThrows<UniqueViolationError> { execute(SQLDialect.POSTGRES, Postgres.unique) }

            assertThat(error.constraint).isEqualTo("authors_name_uq")
            assertThat(error.table).isEqualTo("authors")
        }

        @Test
        fun `reads a failed batch from the error that the driver chains to it`() {
            val error = assertThrows<UniqueViolationError> { execute(SQLDialect.POSTGRES, Postgres.batchUnique) }

            assertThat(error.constraint).isEqualTo("authors_name_uq")
        }
    }

    @Test
    fun `names the constraint from the message on MySQL`() {
        val error = assertThrows<UniqueViolationError> { execute(SQLDialect.MYSQL, MySql.batchUnique) }

        assertThat(error.constraint).isEqualTo("authors_name_uq")
        assertThat(error.table).isEqualTo("authors")
    }

    @Test
    fun `names the constraint from the message on MariaDB`() {
        val error = assertThrows<UniqueViolationError> { execute(SQLDialect.MARIADB, MariaDb.unique) }

        assertThat(error.constraint).isEqualTo("authors_name_uq")
    }

    @Test
    fun `classifies by the SQLSTATE the errors of any other database`() {
        val error = assertThrows<UniqueViolationError> {
            execute(SQLDialect.H2, SQLException("Unique index or primary key violation", "23505"))
        }

        assertThat(error.constraint).isNull()
    }

    @Test
    fun `keeps the exception of jOOQ as the cause, which says the SQL that failed`() {
        val error = assertThrows<UniqueViolationError> { execute(SQLDialect.POSTGRES, Postgres.unique) }

        assertThat(error.cause).isInstanceOf(DataAccessException::class.java)
        assertThat(error.cause!!.message).contains(INSERT)
    }

    @Test
    fun `leaves the exception of jOOQ for an error no adapter recognizes`() {
        val error = assertThrows<DataAccessException> { execute(SQLDialect.POSTGRES, Postgres.tooLong) }

        assertThat(error.sqlState()).isEqualTo("22001")
    }

    private fun execute(dialect: SQLDialect, error: SQLException) {
        val configuration = DefaultConfiguration()
            .set(MockConnection { throw error })
            .set(dialect)
            .set(DefaultExecuteListenerProvider(JooqErrorTranslator()))
        DSL.using(configuration).execute(INSERT)
    }

    private companion object {
        const val INSERT = "insert into authors (id, name) values (2, 'ursula')"
    }
}
