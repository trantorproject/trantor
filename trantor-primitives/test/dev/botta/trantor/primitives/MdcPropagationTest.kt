@file:Suppress("ClassName")

package dev.botta.trantor.primitives

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class MdcPropagationTest {
    @Nested
    inner class `capture` {
        @Test
        fun `takes what the thread has`() {
            MDC.put("correlationId", "abc123")
            MDC.put("userId", "7")

            assertThat(MdcPropagation.capture())
                .containsEntry("correlationId", "abc123")
                .containsEntry("userId", "7")
        }

        @Test
        fun `an empty context is a map, not a null`() {
            assertThat(MdcPropagation.capture()).isEmpty()
        }

        @Test
        fun `what it took does not change afterwards`() {
            MDC.put("correlationId", "abc123")

            val captured = MdcPropagation.capture()
            MDC.put("correlationId", "changed")

            assertThat(captured).containsEntry("correlationId", "abc123")
        }
    }

    @Nested
    inner class `runWithContext` {
        @Test
        fun `the block sees the context it was given`() {
            val seen = MdcPropagation.runWithContext(mapOf("correlationId" to "abc123")) {
                MDC.get("correlationId")
            }

            assertThat(seen).isEqualTo("abc123")
        }

        @Test
        fun `returns what the block returns`() {
            val result = MdcPropagation.runWithContext(emptyMap()) { 42 }

            assertThat(result).isEqualTo(42)
        }

        @Test
        fun `puts back what the thread had`() {
            MDC.put("correlationId", "original")

            MdcPropagation.runWithContext(mapOf("correlationId" to "other")) { }

            assertThat(MDC.get("correlationId")).isEqualTo("original")
        }

        @Test
        fun `the block does not see what the thread had`() {
            MDC.put("userId", "7")

            val seen = MdcPropagation.runWithContext(mapOf("correlationId" to "abc123")) { MDC.get("userId") }

            assertThat(seen).isNull()
        }

        @Test
        fun `puts it back even when the block fails`() {
            MDC.put("correlationId", "original")

            assertThatThrownBy {
                MdcPropagation.runWithContext(mapOf("correlationId" to "other")) { error("boom") }
            }.hasMessage("boom")

            assertThat(MDC.get("correlationId")).isEqualTo("original")
        }

        @Test
        fun `leaves the thread clean when it started clean`() {
            MdcPropagation.runWithContext(mapOf("correlationId" to "abc123")) { }

            assertThat(MdcPropagation.capture()).isEmpty()
        }

        @Test
        fun `nesting restores one level at a time`() {
            MDC.put("level", "outer")

            val seen = MdcPropagation.runWithContext(mapOf("level" to "middle")) {
                MdcPropagation.runWithContext(mapOf("level" to "inner")) { MDC.get("level") }
                    .also { assertThat(MDC.get("level")).isEqualTo("middle") }
            }

            assertThat(seen).isEqualTo("inner")
            assertThat(MDC.get("level")).isEqualTo("outer")
        }
    }

    @AfterEach
    fun leaveTheThreadAsItWasFound() {
        MDC.clear()
    }
}
