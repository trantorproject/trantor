@file:Suppress("ClassName")

package dev.botta.trantor.ai.models.chat

import dev.botta.json.Json
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.models.CallOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class ChatModelExtensionsTest {
    @Test
    fun `generate from a prompt sends a user message`() {
        model.generate("Hola")

        assertThat(model.request?.messages).containsExactly(Message.user("Hola"))
    }

    @Test
    fun `generate from a prompt takes settings`() {
        model.generate("Hola") { maxOutputTokens = 200; temperature = 0.2 }

        assertThat(model.request?.settings).isEqualTo(ChatSettings(maxOutputTokens = 200, temperature = 0.2))
    }

    @Test
    fun `generate from a prompt takes call options`() {
        model.generate("Hola", CallOptions(timeout = 30.seconds))

        assertThat(model.options?.timeout).isEqualTo(30.seconds)
    }

    @Test
    fun `generate from several messages`() {
        model.generate(Message.system("Sos breve"), Message.user("Hola"))

        assertThat(model.request?.messages).containsExactly(Message.system("Sos breve"), Message.user("Hola"))
    }

    @Test
    fun `stream from a prompt sends a user message`() {
        model.stream("Hola").use { }

        assertThat(model.request?.messages).containsExactly(Message.user("Hola"))
    }

    @Test
    fun `text deltas keeps only the text`() {
        model.parts = listOf(
            StreamPart.Raw("response.created", Json.obj()),
            StreamPart.TextDelta("Hola"),
            StreamPart.ReasoningDelta("pensando"),
            StreamPart.TextDelta(" mundo"),
            StreamPart.PartDone(TextPart("Hola mundo")),
        )

        val text = model.stream("Hola").use { it.textDeltas().joinToString("") }

        assertThat(text).isEqualTo("Hola mundo")
    }

    private val model = FakeChatModel()
}
