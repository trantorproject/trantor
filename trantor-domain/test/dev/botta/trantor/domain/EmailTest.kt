@file:Suppress("ClassName")

package dev.botta.trantor.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EmailTest {
    @Nested
    inner class `an address that exists` {
        @Test
        fun `is accepted`() {
            assertThat(Email("nico@example.com").toString()).isEqualTo("nico@example.com")
        }

        @Test
        fun `with a subdomain`() {
            assertThat(Email("nico@mail.example.co.uk")).isNotNull()
        }

        @Test
        fun `with the punctuation the standard allows`() {
            assertThat(Email("nico.bottarini+trantor@example.com")).isNotNull()
        }

        @Test
        fun `written in capitals, because an address is not case sensitive`() {
            assertThat(Email("Nico@Example.COM")).isNotNull()
        }

        @Test
        fun `keeps the capitals it was given, because that is what the user typed`() {
            assertThat(Email("Nico@Example.COM").toString()).isEqualTo("Nico@Example.COM")
        }
    }

    @Nested
    inner class `an address that does not` {
        @Test
        fun `is refused as soon as it is built, never stored half valid`() {
            assertThatThrownBy { Email("not an address") }
                .isInstanceOf(InvalidEmailError::class.java)
                .hasMessageContaining("not an address")
        }

        @Test
        fun `with nothing before the at`() {
            assertThatThrownBy { Email("@example.com") }.isInstanceOf(InvalidEmailError::class.java)
        }

        @Test
        fun `with nothing after it`() {
            assertThatThrownBy { Email("nico@") }.isInstanceOf(InvalidEmailError::class.java)
        }

        @Test
        fun `with no at at all`() {
            assertThatThrownBy { Email("nico.example.com") }.isInstanceOf(InvalidEmailError::class.java)
        }

        @Test
        fun `with a domain that has no dot`() {
            assertThatThrownBy { Email("nico@example") }.isInstanceOf(InvalidEmailError::class.java)
        }

        @Test
        fun `empty`() {
            assertThatThrownBy { Email("") }.isInstanceOf(InvalidEmailError::class.java)
        }

        @Test
        fun `with a space in it`() {
            assertThatThrownBy { Email("ni co@example.com") }.isInstanceOf(InvalidEmailError::class.java)
        }
    }

    @Nested
    inner class `two addresses` {
        @Test
        fun `are the same when they are written the same`() {
            assertThat(Email("nico@example.com")).isEqualTo(Email("nico@example.com"))
        }

        @Test
        fun `are the same whatever the capitals, because mail servers say so`() {
            assertThat(Email("Nico@Example.com")).isEqualTo(Email("nico@example.com"))
        }

        @Test
        fun `of different people are not`() {
            assertThat(Email("nico@example.com")).isNotEqualTo(Email("other@example.com"))
        }

        @Test
        fun `is not equal to the plain string that spells it`() {
            assertThat(Email("nico@example.com")).isNotEqualTo("nico@example.com")
        }

        @Test
        fun `that are equal hash the same, so a set holds one of them`() {
            val addresses = setOf(Email("Nico@Example.com"), Email("nico@example.com"))

            assertThat(addresses).hasSize(1)
        }
    }
}
