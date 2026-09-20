@file:Suppress("ClassName")

package dev.botta.trantor.primitives.lang

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class StringExtensionsTest {
    @Nested
    inner class `ifLengthLessThan` {
        @Test
        fun `runs the block when it is shorter`() {
            "abc".ifLengthLessThan(5) { ran = true }

            assertThat(ran).isTrue()
        }

        @Test
        fun `does not run it when it is exactly that long`() {
            "abcde".ifLengthLessThan(5) { ran = true }

            assertThat(ran).isFalse()
        }

        @Test
        fun `does not run it when it is longer`() {
            "abcdefg".ifLengthLessThan(5) { ran = true }

            assertThat(ran).isFalse()
        }

        @Test
        fun `an empty string is shorter than anything above zero`() {
            "".ifLengthLessThan(1) { ran = true }

            assertThat(ran).isTrue()
        }
    }

    @Nested
    inner class `ifLengthNot` {
        @Test
        fun `runs the block when the length is different`() {
            "abc".ifLengthNot(5) { ran = true }

            assertThat(ran).isTrue()
        }

        @Test
        fun `does not run it when the length matches`() {
            "abcde".ifLengthNot(5) { ran = true }

            assertThat(ran).isFalse()
        }

        @Test
        fun `a longer string is also not that length`() {
            "abcdefg".ifLengthNot(5) { ran = true }

            assertThat(ran).isTrue()
        }
    }

    private var ran = false
}
