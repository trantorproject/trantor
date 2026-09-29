package dev.botta.trantor.data.jdbc.errors

import dev.botta.trantor.data.errors.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.SQLException

class SqlStateErrorAdapterTest {
    @Test
    fun `classifies the constraints the SQL standard tells apart`() {
        assertThat(translate("23505")).isInstanceOf(UniqueViolationError::class.java)
        assertThat(translate("23503")).isInstanceOf(ForeignKeyViolationError::class.java)
        assertThat(translate("23502")).isInstanceOf(NotNullViolationError::class.java)
        assertThat(translate("23514")).isInstanceOf(CheckViolationError::class.java)
    }

    @Test
    fun `does not name the constraint, which the SQLSTATE does not carry`() {
        assertThat((translate("23505") as ConstraintViolationError).constraint).isNull()
    }

    @Test
    fun `takes any other integrity error for a constraint it cannot tell apart`() {
        assertThat(translate("23P01")).isExactlyInstanceOf(ConstraintViolationError::class.java)
    }

    @Test
    fun `leaves alone an error that is not a constraint`() {
        assertThat(translate("22001")).isNull()
        assertThat(translate(null)).isNull()
    }

    private fun translate(sqlState: String?) = SqlStateErrorAdapter.translate(SQLException("Database error", sqlState))
}
