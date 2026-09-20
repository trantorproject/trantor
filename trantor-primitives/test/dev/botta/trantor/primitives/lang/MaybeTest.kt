@file:Suppress("ClassName")

package dev.botta.trantor.primitives.lang

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MaybeTest {
    @Nested
    inner class `a value` {
        @Test
        fun `holds what it was given`() {
            val maybe = Maybe.of("hello")

            assertThat(maybe.hasValue()).isTrue()
            assertThat(maybe.isNone()).isFalse()
            assertThat(maybe.resolveValue()).isEqualTo("hello")
        }

        @Test
        fun `runs the block with it`() {
            var seen: String? = null

            Maybe.of("hello").ifValue { seen = it }

            assertThat(seen).isEqualTo("hello")
        }

        @Test
        fun `null is a value, which is the whole point`() {
            val maybe = Maybe.of<String?>(null)

            assertThat(maybe.hasValue()).isTrue()
            assertThat(maybe.resolveValue()).isNull()
        }

        @Test
        fun `two of the same are equal`() {
            assertThat(Maybe.of("hello")).isEqualTo(Maybe.of("hello"))
            assertThat(Maybe.of("hello")).isNotEqualTo(Maybe.of("bye"))
        }
    }

    @Nested
    inner class `none` {
        @Test
        fun `holds nothing`() {
            val maybe: Maybe<String> = Maybe.None

            assertThat(maybe.isNone()).isTrue()
            assertThat(maybe.hasValue()).isFalse()
            assertThat(maybe.resolveValue()).isNull()
        }

        @Test
        fun `does not run the block`() {
            var ran = false

            (Maybe.None as Maybe<String>).ifValue { ran = true }

            assertThat(ran).isFalse()
        }

        @Test
        fun `is the same none everywhere`() {
            val one: Maybe<String> = Maybe.None
            val other: Maybe<Int> = Maybe.None

            assertThat(one).isSameAs(other)
        }
    }

    @Nested
    inner class `telling one from the other` {
        @Test
        fun `a none is not a value holding null`() {
            val none: Maybe<String?> = Maybe.None
            val nullValue = Maybe.of<String?>(null)

            assertThat(none.resolveValue()).isEqualTo(nullValue.resolveValue())
            assertThat(none).isNotEqualTo(nullValue)
        }

        @Test
        fun `a when over it is exhaustive`() {
            val described = listOf(Maybe.of(1), Maybe.None).map {
                when (it) {
                    is Maybe.Value -> "value ${it.value}"
                    is Maybe.None -> "none"
                }
            }

            assertThat(described).containsExactly("value 1", "none")
        }
    }
}
