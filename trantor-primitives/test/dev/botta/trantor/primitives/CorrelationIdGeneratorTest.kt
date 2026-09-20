@file:Suppress("ClassName")

package dev.botta.trantor.primitives

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CorrelationIdGeneratorTest {
    @Test
    fun `is short enough to read in a log line`() {
        assertThat(CorrelationIdGenerator.new()).hasSize(10)
    }

    @Test
    fun `is hexadecimal, with no dashes to break a search`() {
        assertThat(CorrelationIdGenerator.new()).matches("[0-9a-f]{10}")
    }

    @Test
    fun `gives a different one every time`() {
        val ids = (1..1000).map { CorrelationIdGenerator.new() }

        assertThat(ids).doesNotHaveDuplicates()
    }
}
