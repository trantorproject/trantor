@file:Suppress("ClassName")

package dev.botta.trantor.data.jdbc.errors

import dev.botta.trantor.data.errors.*
import dev.botta.trantor.data.testing.RecordedErrors.MariaDb
import dev.botta.trantor.data.testing.RecordedErrors.MySql
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.sql.SQLException

class MySqlErrorAdapterTest {
    @Nested
    inner class `on MySQL` {
        @Test
        fun `names the unique key and its table`() {
            val error = translate(MySql.unique)

            assertThat(error).isInstanceOf(UniqueViolationError::class.java)
            assertThat((error as UniqueViolationError).constraint).isEqualTo("authors_name_uq")
            assertThat(error.table).isEqualTo("authors")
        }

        @Test
        fun `takes a repeated primary key for a unique violation`() {
            val error = translate(MySql.primaryKey)

            assertThat(error).isInstanceOf(UniqueViolationError::class.java)
            assertThat((error as UniqueViolationError).constraint).isEqualTo("PRIMARY")
        }

        @Test
        fun `names the foreign key and the table that holds it when a delete leaves rows pointing to nothing`() {
            val error = translate(MySql.foreignKeyOnDelete)

            assertThat(error).isInstanceOf(ForeignKeyViolationError::class.java)
            assertThat((error as ForeignKeyViolationError).constraint).isEqualTo("books_author_fk")
            assertThat(error.table).isEqualTo("books")
        }

        @Test
        fun `names the foreign key and the table that holds it when a row points to one that does not exist`() {
            val error = translate(MySql.foreignKeyOnInsert)

            assertThat(error).isInstanceOf(ForeignKeyViolationError::class.java)
            assertThat((error as ForeignKeyViolationError).constraint).isEqualTo("books_author_fk")
            assertThat(error.table).isEqualTo("books")
        }

        @Test
        fun `names the column that cannot be null`() {
            val error = translate(MySql.notNull)

            assertThat(error).isInstanceOf(NotNullViolationError::class.java)
            assertThat((error as NotNullViolationError).column).isEqualTo("name")
        }

        @Test
        fun `takes a column left out that has no default for one that cannot be null`() {
            val error = translate(MySql.noDefault)

            assertThat(error).isInstanceOf(NotNullViolationError::class.java)
            assertThat((error as NotNullViolationError).column).isEqualTo("name")
        }

        @Test
        fun `names the check constraint, which MySQL reports outside the SQLSTATE of constraints`() {
            val error = translate(MySql.check)

            assertThat(error).isInstanceOf(CheckViolationError::class.java)
            assertThat((error as CheckViolationError).constraint).isEqualTo("authors_name_ck")
        }

        @Test
        fun `leaves alone an error that is not a constraint`() {
            assertThat(translate(MySql.tooLong)).isNull()
        }
    }

    @Nested
    inner class `on MariaDB` {
        @Test
        fun `names the unique key, without the table MariaDB does not report`() {
            val error = translate(MariaDb.unique)

            assertThat(error).isInstanceOf(UniqueViolationError::class.java)
            assertThat((error as UniqueViolationError).constraint).isEqualTo("authors_name_uq")
            assertThat(error.table).isNull()
        }

        @Test
        fun `names the foreign key and the table that holds it`() {
            val error = translate(MariaDb.foreignKeyOnDelete)

            assertThat(error).isInstanceOf(ForeignKeyViolationError::class.java)
            assertThat((error as ForeignKeyViolationError).constraint).isEqualTo("books_author_fk")
            assertThat(error.table).isEqualTo("books")
        }

        @Test
        fun `names the column that cannot be null`() {
            val error = translate(MariaDb.notNull)

            assertThat(error).isInstanceOf(NotNullViolationError::class.java)
            assertThat((error as NotNullViolationError).column).isEqualTo("name")
        }

        @Test
        fun `names the check constraint and its table`() {
            val error = translate(MariaDb.check)

            assertThat(error).isInstanceOf(CheckViolationError::class.java)
            assertThat((error as CheckViolationError).constraint).isEqualTo("authors_name_ck")
            assertThat(error.table).isEqualTo("authors")
        }
    }

    private fun translate(error: SQLException) = MySqlErrorAdapter.translate(error)
}
