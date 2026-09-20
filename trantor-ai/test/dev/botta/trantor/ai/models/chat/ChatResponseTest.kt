@file:Suppress("ClassName")

package dev.botta.trantor.ai.models.chat

import dev.botta.json.Json
import dev.botta.trantor.ai.models.ResponseInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class ChatResponseTest {
    @Test
    fun `text joins every text part`() {
        val response = responseOf(TextPart("Hola "), TextPart("mundo"))

        assertThat(response.text).isEqualTo("Hola mundo")
    }

    @Test
    fun `text ignores parts that are not text`() {
        val response = responseOf(ReasoningPart("pensando"), TextPart("Hola"))

        assertThat(response.text).isEqualTo("Hola")
    }

    @Test
    fun `text of a response without text is empty`() {
        val response = responseOf(toolCall)

        assertThat(response.text).isEmpty()
    }

    @Test
    fun `tool calls returns the calls the model asked for`() {
        val response = responseOf(TextPart("Busco el clima"), toolCall)

        assertThat(response.toolCalls).containsExactly(toolCall)
    }

    @Test
    fun `refusal returns the text of the refusal`() {
        val response = responseOf(RefusalPart("No puedo ayudar con eso"))

        assertThat(response.refusal).isEqualTo("No puedo ayudar con eso")
    }

    @Test
    fun `refusal is null when the model answered`() {
        val response = responseOf(TextPart("Hola"))

        assertThat(response.refusal).isNull()
    }

    @Test
    fun `as message keeps the content to continue the conversation`() {
        val response = responseOf(TextPart("Hola"), toolCall)

        assertThat(response.asMessage()).isEqualTo(Message.Assistant(listOf(TextPart("Hola"), toolCall)))
    }

    private fun responseOf(vararg content: Part) = ChatResponse(
        content = content.toList(),
        finishReason = FinishReasons.Stop,
        info = ResponseInfo(model = "gpt-4.1-mini", provider = "openai", latency = 10.milliseconds),
    )

    private val toolCall = ToolCallPart("call_1", "getWeather", Json.obj("city" to "Bariloche"))
}
