package dev.botta.trantor.ai.providers.anthropic

import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A request Claude Sonnet 5.5 turned down, recorded: a system prompt asking it to write out, between steps, what it
 * found and what it meant, which its safeguards took for a request of its reasoning. Nothing came back but the stop
 * reason and its details.
 */
class AnthropicRefusalTest {
    @Test
    fun `says why the safeguards declined, as the provider named it`() {
        http.body = fixture("chat/refusal-1.json")

        val response = model.generate(request)

        assertThat(response.finishReason).isEqualTo(FinishReasons.Refusal)
        val refusal = response.content.filterIsInstance<RefusalPart>().single()
        assertThat(refusal.category).isEqualTo("reasoning_extraction")
        assertThat(refusal.text).startsWith("This request was blocked")
        assertThat(response.refusal).isEqualTo(refusal.text)
    }

    @Test
    fun `and so does a stream`() {
        http.body = fixture("chat/refusal-stream-1.txt")

        val response = model.stream(request).use { stream -> stream.forEach { }; stream.response() }

        assertThat(response.content.filterIsInstance<RefusalPart>().single().category).isEqualTo("reasoning_extraction")
    }

    private val request = ChatRequest(listOf(Message.user("Soy Ana Pérez. ¿Dónde está mi último pedido?")))

    private fun fixture(name: String) =
        javaClass.getResource("/anthropic/$name")?.readText() ?: error("Missing fixture $name")

    private val http = FakeHttpClient()
    private val model = AnthropicChatModel("claude-sonnet-5-5", AnthropicConfig(apiKey = "sk-ant-test"), http)
}
