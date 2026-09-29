@file:Suppress("ClassName")

package dev.botta.trantor.data.jdbc.errors

import dev.botta.trantor.data.errors.*
import dev.botta.trantor.data.testing.RecordedErrors.Postgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import org.postgresql.util.PSQLState
import java.sql.SQLException

class PostgresErrorAdapterTest {
    @Test
    fun `names the unique constraint and its table`() {
        val error = translate(Postgres.unique)

        assertThat(error).isInstanceOf(UniqueViolationError::class.java)
        assertThat((error as UniqueViolationError).constraint).isEqualTo("authors_name_uq")
        assertThat(error.table).isEqualTo("authors")
    }

    @Test
    fun `takes a repeated primary key for a unique violation`() {
        val error = translate(Postgres.primaryKey)

        assertThat(error).isInstanceOf(UniqueViolationError::class.java)
        assertThat((error as UniqueViolationError).constraint).isEqualTo("authors_pkey")
    }

    @Nested
    inner class `a foreign key` {
        @Test
        fun `is named with the table that holds it when a delete leaves rows pointing to nothing`() {
            val error = translate(Postgres.foreignKeyOnDelete)

            assertThat(error).isInstanceOf(ForeignKeyViolationError::class.java)
            assertThat((error as ForeignKeyViolationError).constraint).isEqualTo("books_author_fk")
            assertThat(error.table).isEqualTo("books")
        }

        @Test
        fun `is named with the table that holds it when a row points to one that does not exist`() {
            val error = translate(Postgres.foreignKeyOnInsert)

            assertThat(error).isInstanceOf(ForeignKeyViolationError::class.java)
            assertThat((error as ForeignKeyViolationError).constraint).isEqualTo("books_author_fk")
            assertThat(error.table).isEqualTo("books")
        }
    }

    @Test
    fun `names the column that cannot be null and its table`() {
        val error = translate(Postgres.notNull)

        assertThat(error).isInstanceOf(NotNullViolationError::class.java)
        assertThat((error as NotNullViolationError).column).isEqualTo("name")
        assertThat(error.table).isEqualTo("authors")
    }

    @Test
    fun `names the check constraint and its table`() {
        val error = translate(Postgres.check)

        assertThat(error).isInstanceOf(CheckViolationError::class.java)
        assertThat((error as CheckViolationError).constraint).isEqualTo("authors_name_ck")
        assertThat(error.table).isEqualTo("authors")
    }

    @Test
    fun `leaves alone an error that is not a constraint`() {
        assertThat(translate(Postgres.tooLong)).isNull()
    }

    @Test
    fun `classifies by its SQLSTATE an error that did not come from the server`() {
        val error = translate(PSQLException("Duplicate key", PSQLState.UNIQUE_VIOLATION))

        assertThat(error).isInstanceOf(UniqueViolationError::class.java)
        assertThat((error as UniqueViolationError).constraint).isNull()
    }

    @Test
    fun `keeps the cause it is given`() {
        val cause = RuntimeException("What jOOQ threw")

        val error = PostgresErrorAdapter.translate(Postgres.unique, cause)

        assertThat(error!!.cause).isSameAs(cause)
    }

    private fun translate(error: SQLException) = PostgresErrorAdapter.translate(error)
}
