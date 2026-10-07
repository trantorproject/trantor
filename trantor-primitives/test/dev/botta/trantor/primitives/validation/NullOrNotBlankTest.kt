package dev.botta.trantor.primitives.validation

import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.validator.HibernateValidator
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator
import org.junit.jupiter.api.Test
import java.util.Locale

class NullOrNotBlankTest {
    @Test
    fun `accepts null, which is what a partial update sends for a field it leaves alone`() {
        assertThat(validate(RenameBook(null))).isEmpty()
    }

    @Test
    fun `accepts text`() {
        assertThat(validate(RenameBook("Dune"))).isEmpty()
    }

    @Test
    fun `refuses an empty text`() {
        assertThat(validate(RenameBook(""))).containsExactly("title: must not be blank")
    }

    @Test
    fun `refuses a text that is only whitespace`() {
        assertThat(validate(RenameBook(" \t "))).containsExactly("title: must not be blank")
    }

    private fun validate(request: RenameBook) =
        validator.validate(request).map { "${it.propertyPath}: ${it.message}" }

    class RenameBook(@NullOrNotBlank val title: String?)

    // In English whatever the machine speaks: the interpolator translates the message to the locale of the JVM
    private val validator = Validation.byProvider(HibernateValidator::class.java)
        .configure()
        .messageInterpolator(ParameterMessageInterpolator(setOf(Locale.ENGLISH), Locale.ENGLISH, false))
        .buildValidatorFactory()
        .validator
}
