package dev.botta.trantor.ai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TrantorAITest {
    @Test
    fun `module is available`() {
        assertThat(TrantorAI.NAME).isEqualTo("trantor-ai")
    }
}
