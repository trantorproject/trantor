@file:Suppress("ClassName")

package dev.botta.trantor.ai.models.chat

import dev.botta.trantor.ai.tools.ToolOutput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MessageTest {
    @Test
    fun `user message from text`() {
        assertThat(Message.user("Hola")).isEqualTo(Message.User(listOf(TextPart("Hola"))))
    }

    @Test
    fun `assistant message from text`() {
        assertThat(Message.assistant("Hola")).isEqualTo(Message.Assistant(listOf(TextPart("Hola"))))
    }

    @Test
    fun `system message from text`() {
        assertThat(Message.system("Sos un asistente")).isEqualTo(Message.System("Sos un asistente"))
    }

    @Test
    fun `tool message from a single result`() {
        val result = ToolResultPart("call_1", "getWeather", ToolOutput.Text("18 grados"))

        assertThat(Message.toolResult(result)).isEqualTo(Message.Tool(listOf(result)))
    }
}
