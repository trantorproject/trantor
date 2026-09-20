@file:Suppress("ClassName")

package dev.botta.trantor.core.validation

import dev.botta.cqbus.ExecutionContext
import dev.botta.cqbus.requests.Request
import jakarta.validation.Validation
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.validator.HibernateValidator
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ValidationMiddlewareTest {
    @Nested
    inner class `a request that holds up` {
        @Test
        fun `reaches its handler, and the answer comes back`() {
            val answer = middleware.execute(PlaceOrder("nico", 1), { handle() }, ExecutionContext())

            assertThat(answer).isEqualTo("order-1")
            assertThat(calls).containsExactly("handled")
        }

        @Test
        fun `a request with nothing to validate goes through`() {
            assertThatCode { middleware.execute(Ping(), { handle() }, ExecutionContext()) }
                .doesNotThrowAnyException()
        }
    }

    @Nested
    inner class `a request that does not` {
        @Test
        fun `never reaches its handler`() {
            assertThatThrownBy { validate(PlaceOrder("", 1)) }

            assertThat(calls).isEmpty()
        }

        @Test
        fun `fails with the field that is wrong`() {
            assertThatThrownBy { validate(PlaceOrder("", 1)) }
                .isInstanceOf(ValidationError::class.java)
                .hasMessageContaining("customer")
        }

        @Test
        fun `carries every violation, not just the first`() {
            val error = catchValidationError(PlaceOrder("", 0))

            assertThat(error.errors.map { it.field }).containsExactlyInAnyOrder("customer", "quantity")
        }

        @Test
        fun `each violation says what is wrong with it`() {
            val error = catchValidationError(PlaceOrder("", 1))

            assertThat(error.errors.single().message).isNotBlank()
        }

        @Test
        fun `a nested field is named by its path`() {
            val error = catchValidationError(PlaceOrder("nico", 1, Address("")))

            assertThat(error.errors.single().field).isEqualTo("address.city")
        }
    }

    private fun validate(request: PlaceOrder) {
        middleware.execute(request, { handle() }, ExecutionContext())
    }

    private fun handle(): String {
        calls.add("handled")

        return "order-1"
    }

    private fun catchValidationError(request: PlaceOrder): ValidationError =
        runCatching { validate(request) }.exceptionOrNull() as ValidationError

    class PlaceOrder(
        @field:NotBlank val customer: String,
        @field:Min(1) val quantity: Int,
        @field:jakarta.validation.Valid val address: Address? = null,
    ): Request<String>

    class Address(@field:Size(min = 1) val city: String)

    class Ping: Request<String>

    private val calls = mutableListOf<String>()

    private val validator = Validation.byProvider(HibernateValidator::class.java)
        .configure()
        .messageInterpolator(ParameterMessageInterpolator())
        .buildValidatorFactory()
        .validator

    private val middleware = ValidationMiddleware(validator)
}
