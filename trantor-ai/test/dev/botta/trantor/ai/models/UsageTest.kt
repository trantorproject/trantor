@file:Suppress("ClassName")

package dev.botta.trantor.ai.models

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UsageTest {
    @Test
    fun `total is input plus output`() {
        val usage = Usage(inputTokens = 100, outputTokens = 20)

        assertThat(usage.totalTokens).isEqualTo(120)
    }

    @Test
    fun `total does not count details twice`() {
        val usage = Usage(inputTokens = 100, outputTokens = 20, cacheReadTokens = 80, reasoningTokens = 15)

        assertThat(usage.totalTokens).isEqualTo(120)
    }

    @Test
    fun `total is unknown when nothing was reported`() {
        assertThat(Usage.Unknown.totalTokens).isNull()
    }

    @Test
    fun `total of a partial usage counts what is known`() {
        val usage = Usage(outputTokens = 20)

        assertThat(usage.totalTokens).isEqualTo(20)
    }

    @Test
    fun `the input that went through no cache is what is left of it once reads and writes are taken out`() {
        val usage = Usage(inputTokens = 1000, cacheReadTokens = 800, cacheWriteTokens = 150)

        assertThat(usage.uncachedInputTokens).isEqualTo(50)
    }

    @Test
    fun `and it is the whole input when the provider says nothing of a cache`() {
        val usage = Usage(inputTokens = 1000)

        assertThat(usage.uncachedInputTokens).isEqualTo(1000)
    }

    @Test
    fun `and unknown when the input is`() {
        val usage = Usage(outputTokens = 20, cacheReadTokens = 800)

        assertThat(usage.uncachedInputTokens).isNull()
    }

    @Test
    fun `adds up usages of several calls`() {
        val first = Usage(inputTokens = 100, outputTokens = 20, cacheReadTokens = 80, cacheWriteTokens = 10, reasoningTokens = 5)
        val second = Usage(inputTokens = 200, outputTokens = 30, cacheReadTokens = 150, cacheWriteTokens = 1, reasoningTokens = 7)

        val total = first + second

        assertThat(total).isEqualTo(
            Usage(inputTokens = 300, outputTokens = 50, cacheReadTokens = 230, cacheWriteTokens = 11, reasoningTokens = 12)
        )
    }

    @Test
    fun `adding an unknown usage keeps what is known`() {
        val usage = Usage(inputTokens = 100, outputTokens = 20)

        assertThat(usage + Usage.Unknown).isEqualTo(usage)
    }

    @Test
    fun `adding two unknown usages stays unknown`() {
        assertThat(Usage.Unknown + Usage.Unknown).isEqualTo(Usage.Unknown)
    }

    @Test
    fun `adding a usage that only reports output keeps input unknown`() {
        val total = Usage.Unknown + Usage(outputTokens = 20)

        assertThat(total.inputTokens).isNull()
        assertThat(total.outputTokens).isEqualTo(20)
    }
}
