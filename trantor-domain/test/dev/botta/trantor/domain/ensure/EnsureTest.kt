@file:Suppress("ClassName")

package dev.botta.trantor.domain.ensure

import dev.botta.time.Clock
import dev.botta.trantor.domain.errors.DomainError
import dev.botta.trantor.domain.errors.InvalidArgumentError
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class EnsureTest {
    @Nested
    inner class `booleans` {
        @Test
        fun `isTrue lets a true through and stops a false`() {
            assertThatCode { Ensure.isTrue(true) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.isTrue(false, "accepted") }.hasMessage("accepted must be true")
        }

        @Test
        fun `isFalse is the other way round`() {
            assertThatCode { Ensure.isFalse(false) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.isFalse(true, "cancelled") }.hasMessage("cancelled must be false")
        }
    }

    @Nested
    inner class `strings` {
        @Test
        fun `notBlank refuses whitespace, which notEmpty allows`() {
            assertThatThrownBy { Ensure.notBlank("   ", "name") }.hasMessage("name must not be blank")
            assertThatCode { Ensure.notEmpty("   ", "name") }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.notEmpty("", "name") }.hasMessage("name must not be empty")
        }

        @Test
        fun `length is checked against a range, and the message says what it got`() {
            assertThatCode { Ensure.lengthBetween("abc", 1..5) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.lengthBetween("abcdef", 1..5, "name") }
                .hasMessage("name length must be in 1..5, got 6")
        }

        @Test
        fun `minLength and maxLength are the open ended versions`() {
            assertThatThrownBy { Ensure.minLength("ab", 3, "name") }.hasMessage("name length must be at least 3, got 2")
            assertThatThrownBy { Ensure.maxLength("abcd", 3, "name") }.hasMessage("name length must be at most 3, got 4")
        }

        @Test
        fun `matches wants the whole string, not a piece of it`() {
            assertThatCode { Ensure.matches("A-123", Regex("[A-Z]-\\d+")) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.matches("xA-123", Regex("[A-Z]-\\d+"), "code") }
                .hasMessage("code has invalid format")
        }

        @Test
        fun `startsWith and endsWith`() {
            assertThatCode { Ensure.startsWith("order-7", "order-") }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.startsWith("x", "order-", "id") }.hasMessage("id must start with 'order-'")
            assertThatThrownBy { Ensure.endsWith("x", ".pdf", "file") }.hasMessage("file must end with '.pdf'")
        }

        @Test
        fun `contains and notContains say which way they failed`() {
            assertThatThrownBy { Ensure.contains("hola", "chau", "greeting") }
                .hasMessage("greeting must contain 'chau'")
            assertThatThrownBy { Ensure.notContains("hola chau", "chau", "greeting") }
                .hasMessage("greeting must not contain 'chau'")
        }
    }

    @Nested
    inner class `numbers` {
        @Test
        fun `positive does not count zero`() {
            assertThatCode { Ensure.positive(1) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.positive(0, "total") }.hasMessage("total must be positive, got 0")
        }

        @Test
        fun `nonNegative does`() {
            assertThatCode { Ensure.nonNegative(0) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.nonNegative(-1, "total") }.hasMessage("total must be non-negative, got -1")
        }

        @Test
        fun `min and max include the bound`() {
            assertThatCode { Ensure.min(3, 3) }.doesNotThrowAnyException()
            assertThatCode { Ensure.max(3, 3) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.min(2, 3, "n") }.hasMessage("n must be at least 3, got 2")
            assertThatThrownBy { Ensure.max(4, 3, "n") }.hasMessage("n must be at most 3, got 4")
        }

        @Test
        fun `inRange is closed on both sides`() {
            assertThatCode { Ensure.inRange(1, 1..5) }.doesNotThrowAnyException()
            assertThatCode { Ensure.inRange(5, 1..5) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.inRange(6, 1..5, "n") }.hasMessage("n must be in 1..5, got 6")
        }

        @Test
        fun `longs get the same checks as ints`() {
            assertThatCode { Ensure.positive(1L) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.inRange(6L, 1L..5L, "n") }.hasMessage("n must be in 1..5, got 6")
        }

        @Test
        fun `a double that is not a number is caught by finite`() {
            assertThatCode { Ensure.finite(1.5) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.finite(Double.NaN, "ratio") }.isInstanceOf(DomainError::class.java)
            assertThatThrownBy { Ensure.finite(Double.POSITIVE_INFINITY, "ratio") }
                .isInstanceOf(DomainError::class.java)
        }

        @Test
        fun `money is checked as BigDecimal, where scale does not change the value`() {
            assertThatCode { Ensure.positive(BigDecimal("0.01")) }.doesNotThrowAnyException()
            assertThatCode { Ensure.nonNegative(BigDecimal("0.00")) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.positive(BigDecimal("0.00"), "total") }
                .isInstanceOf(DomainError::class.java)
        }
    }

    @Nested
    inner class `comparables` {
        @Test
        fun `greaterThan is strict, greaterThanOrEqual is not`() {
            assertThatCode { Ensure.greaterThan(2, 1) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.greaterThan(1, 1, "n") }.isInstanceOf(DomainError::class.java)
            assertThatCode { Ensure.greaterThanOrEqual(1, 1) }.doesNotThrowAnyException()
        }

        @Test
        fun `lessThan and lessThanOrEqual are the mirror`() {
            assertThatCode { Ensure.lessThan(1, 2) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.lessThan(2, 2, "n") }.isInstanceOf(DomainError::class.java)
            assertThatCode { Ensure.lessThanOrEqual(2, 2) }.doesNotThrowAnyException()
        }

        @Test
        fun `between works on anything comparable, not only numbers`() {
            assertThatCode { Ensure.between("m", "a".."z") }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.between("A", "a".."z", "letter") }.isInstanceOf(DomainError::class.java)
        }
    }

    @Nested
    inner class `equality` {
        @Test
        fun `equal and notEqual`() {
            assertThatCode { Ensure.equal("a", "a") }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.equal("a", "b", "code") }.isInstanceOf(DomainError::class.java)
            assertThatCode { Ensure.notEqual("a", "b") }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.notEqual("a", "a", "code") }.isInstanceOf(DomainError::class.java)
        }

        @Test
        fun `nulls are values too`() {
            assertThatCode { Ensure.equal(null, null) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.equal(null, "a", "code") }.isInstanceOf(DomainError::class.java)
        }
    }

    @Nested
    inner class `collections` {
        @Test
        fun `notEmpty and empty`() {
            assertThatCode { Ensure.notEmpty(listOf(1)) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.notEmpty(emptyList<Int>(), "items") }
                .hasMessage("items must not be empty")
            assertThatThrownBy { Ensure.empty(listOf(1), "items") }.isInstanceOf(DomainError::class.java)
        }

        @Test
        fun `size is checked the same way as string length`() {
            assertThatCode { Ensure.sizeBetween(listOf(1, 2), 1..3) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.minSize(listOf(1), 2, "items") }
                .hasMessage("items size must be at least 2, got 1")
            assertThatThrownBy { Ensure.maxSize(listOf(1, 2), 1, "items") }
                .hasMessage("items size must be at most 1, got 2")
        }

        @Test
        fun `contains and notContains`() {
            assertThatCode { Ensure.contains(listOf(1, 2), 1) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.contains(listOf(1, 2), 3, "items") }.hasMessage("items must contain 3")
            assertThatThrownBy { Ensure.notContains(listOf(1, 2), 1, "items") }.hasMessage("items must not contain 1")
        }

        @Test
        fun `unique catches a repeat`() {
            assertThatCode { Ensure.unique(listOf(1, 2, 3)) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.unique(listOf(1, 2, 2), "items") }
                .hasMessage("items must contain unique elements")
        }

        @Test
        fun `allMatch and noneMatch take the condition`() {
            assertThatCode { Ensure.allMatch(listOf(2, 4)) { it % 2 == 0 } }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.allMatch(listOf(2, 3), "items") { it % 2 == 0 } }
                .isInstanceOf(DomainError::class.java)
            assertThatCode { Ensure.noneMatch(listOf(1, 3)) { it % 2 == 0 } }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.noneMatch(listOf(1, 2), "items") { it % 2 == 0 } }
                .isInstanceOf(DomainError::class.java)
        }

        @Test
        fun `an empty collection matches everything and nothing at once`() {
            assertThatCode { Ensure.allMatch(emptyList<Int>()) { false } }.doesNotThrowAnyException()
            assertThatCode { Ensure.noneMatch(emptyList<Int>()) { true } }.doesNotThrowAnyException()
        }
    }

    @Nested
    inner class `maps` {
        @Test
        fun `notEmpty and hasKey`() {
            assertThatCode { Ensure.notEmpty(mapOf("a" to 1)) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.notEmpty(emptyMap<String, Int>(), "headers") }
                .hasMessage("headers must not be empty")
            assertThatCode { Ensure.hasKey(mapOf("a" to 1), "a") }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.hasKey(mapOf("a" to 1), "b", "headers") }
                .hasMessage("headers must contain key b")
        }
    }

    @Nested
    inner class `dates` {
        @Test
        fun `inPast and inFuture are read against the Trantor clock`() {
            Clock.stoppedAt(LocalDateTime.of(2026, 9, 20, 12, 0))

            assertThatCode { Ensure.inPast(LocalDate.of(2026, 9, 19)) }.doesNotThrowAnyException()
            assertThatCode { Ensure.inFuture(LocalDate.of(2026, 9, 21)) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.inPast(LocalDate.of(2026, 9, 21), "bornOn") }
                .isInstanceOf(DomainError::class.java)
        }

        @Test
        fun `before and after compare two moments`() {
            val earlier = LocalDateTime.of(2026, 9, 20, 10, 0)
            val later = LocalDateTime.of(2026, 9, 20, 12, 0)

            assertThatCode { Ensure.before(earlier, later) }.doesNotThrowAnyException()
            assertThatCode { Ensure.after(later, earlier) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.before(later, earlier, "startsAt") }.isInstanceOf(DomainError::class.java)
        }
    }

    @Nested
    inner class `oneOf` {
        @Test
        fun `keeps a value inside the set it belongs to`() {
            assertThatCode { Ensure.oneOf(Status.Active, setOf(Status.Active, Status.Paused)) }
                .doesNotThrowAnyException()
            assertThatThrownBy { Ensure.oneOf(Status.Closed, setOf(Status.Active), "status") }
                .isInstanceOf(DomainError::class.java)
        }

        @Test
        fun `works for things that are not enums too`() {
            assertThatCode { Ensure.oneOf("ars", listOf("ars", "usd")) }.doesNotThrowAnyException()
            assertThatThrownBy { Ensure.oneOf("eur", listOf("ars", "usd"), "currency") }
                .isInstanceOf(DomainError::class.java)
        }
    }

    @Nested
    inner class `the error it throws` {
        @Test
        fun `is a DomainError, so a web layer can map it to a 400`() {
            assertThatThrownBy { Ensure.positive(0) }.isInstanceOf(DomainError::class.java)
        }

        @Test
        fun `says which field it was, because a caller only sees the message`() {
            assertThatThrownBy { Ensure.positive(0, "totalAmount") }.hasMessageContaining("totalAmount")
        }

        @Test
        fun `is called value when nobody named the field`() {
            assertThatThrownBy { Ensure.positive(0) }.hasMessage("value must be positive, got 0")
        }

        @Test
        fun `can be the domain's own, which is the point of the second form`() {
            assertThatThrownBy { Ensure.positive(0) { InvalidArgumentError("total", "An order cannot be free") } }
                .isInstanceOf(InvalidArgumentError::class.java)
                .hasMessage("An order cannot be free")
        }

        @Test
        fun `is not built when the check passes`() {
            var built = false

            Ensure.positive(1) { built = true; InvalidArgumentError("total") }

            assertThat(built).isFalse()
        }
    }

    @Nested
    inner class `the ensure block` {
        @Test
        fun `runs every check until one fails`() {
            val checked = mutableListOf<String>()

            assertThatThrownBy {
                ensure {
                    notBlank("nico", "name").also { checked.add("name") }
                    positive(0, "total").also { checked.add("total") }
                    notBlank("", "email").also { checked.add("email") }
                }
            }.hasMessage("total must be positive, got 0")

            assertThat(checked).containsExactly("name")
        }

        @Test
        fun `lets everything through when it all holds`() {
            assertThatCode {
                ensure {
                    notBlank("nico", "name")
                    positive(1, "total")
                }
            }.doesNotThrowAnyException()
        }
    }

    @AfterEach
    fun letTheClockRunAgain() {
        Clock.live()
    }

    private enum class Status { Active, Paused, Closed }
}
