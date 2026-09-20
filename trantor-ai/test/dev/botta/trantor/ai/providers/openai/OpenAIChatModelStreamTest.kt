@file:Suppress("ClassName")

package dev.botta.trantor.ai.providers.openai

import dev.botta.json.Json
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.ai.errors.AuthenticationError
import dev.botta.trantor.ai.errors.ProviderError
import dev.botta.trantor.ai.models.chat.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OpenAIChatModelStreamTest {
    @Test
    fun `asks the provider to stream`() {
        httpClient.body = fixture("stream-text.txt")

        model.stream(ChatRequest(Message.user("Hola"))).use { it.response() }

        assertThat(sentBody()["stream"]?.asBoolean()).isTrue()
    }

    @Test
    fun `returns the text deltas as they arrive`() {
        httpClient.body = fixture("stream-text.txt")

        val deltas = model.stream(ChatRequest(Message.user("Hola"))).use { stream ->
            stream.asSequence().filterIsInstance<StreamPart.TextDelta>().map { it.text }.toList()
        }

        assertThat(deltas.take(4)).containsExactly("¡", "Claro", "!", " Aquí")
        assertThat(deltas.joinToString("")).isEqualTo(JOKE)
    }

    @Test
    fun `returns a finished item as a part`() {
        httpClient.body = fixture("stream-text.txt")

        val parts = model.stream(ChatRequest(Message.user("Hola"))).use { stream ->
            stream.asSequence().filterIsInstance<StreamPart.PartDone>().map { it.part }.toList()
        }

        assertThat(parts).containsExactly(TextPart(JOKE))
    }

    @Test
    fun `returns the events it does not map as raw`() {
        httpClient.body = fixture("stream-text.txt")

        val raw = model.stream(ChatRequest(Message.user("Hola"))).use { stream ->
            stream.asSequence().filterIsInstance<StreamPart.Raw>().map { it.event }.toList()
        }

        assertThat(raw).containsExactly(
            "response.created",
            "response.in_progress",
            "response.output_item.added",
            "response.content_part.added",
            "response.output_text.done",
            "response.content_part.done",
        )
    }

    @Test
    fun `the aggregated response is the same one generate would return`() {
        httpClient.body = fixture("stream-text.txt")

        val response = model.stream(ChatRequest(Message.user("Hola"))).use { stream ->
            stream.asSequence().toList()
            stream.response()
        }

        assertThat(response.text).isEqualTo(JOKE)
        assertThat(response.finishReason).isEqualTo(FinishReasons.Stop)
        assertThat(response.usage.inputTokens).isEqualTo(13)
        assertThat(response.usage.outputTokens).isEqualTo(30)
        assertThat(response.info.id).isEqualTo("resp_0bb1179230eef44f006aaf3459607c87d2baef55f95d711d6b")
        assertThat(response.info.model).isEqualTo("gpt-4.1-mini-2025-04-14")
    }

    @Test
    fun `response consumes what is left of the stream`() {
        httpClient.body = fixture("stream-text.txt")

        val response = model.stream(ChatRequest(Message.user("Hola"))).use { it.response() }

        assertThat(response.text).isEqualTo(JOKE)
        assertThat(response.finishReason).isEqualTo(FinishReasons.Stop)
    }

    @Test
    fun `a stream cut before the final event returns what arrived, with a warning`() {
        httpClient.body = fixture("stream-text.txt").substringBefore("event: response.output_text.done")

        val response = model.stream(ChatRequest(Message.user("Hola"))).use { it.response() }

        assertThat(response.text).isEqualTo(JOKE)
        assertThat(response.finishReason).isEqualTo(FinishReasons.Other)
        assertThat(response.rawFinishReason).isEqualTo("stream_ended_without_response")
        assertThat(response.warnings.map { it.message }).contains("The stream ended before the final response event")
    }

    @Test
    fun `an error event cuts the stream`() {
        httpClient.body = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"Hola"}

            event: error
            data: {"type":"error","code":"server_error","message":"Something went wrong"}

        """.trimIndent()

        model.stream(ChatRequest(Message.user("Hola"))).use { stream ->
            assertThat(stream.next()).isEqualTo(StreamPart.TextDelta("Hola"))
            assertThatThrownBy { stream.next() }
                .isInstanceOf(ProviderError::class.java)
                .hasMessageContaining("Something went wrong")
        }
    }

    @Test
    fun `an error status is raised before streaming`() {
        httpClient.status = 401
        httpClient.body = fixture("error-401.json")

        assertThatThrownBy { model.stream(ChatRequest(Message.user("Hola"))) }
            .isInstanceOf(AuthenticationError::class.java)
    }

    @Test
    fun `closing the stream cancels the call`() {
        httpClient.body = fixture("stream-text.txt")

        model.stream(ChatRequest(Message.user("Hola"))).use { it.next() }

        assertThat(httpClient.wasCancelled).isTrue()
        assertThat(httpClient.wasClosed).isTrue()
    }

    private fun sentBody() = Json.parse(httpClient.requestBody!!).asObject()!!

    private fun fixture(name: String) =
        javaClass.getResource("/openai/$name")?.readText() ?: error("Missing fixture $name")

    companion object {
        private const val JOKE = "¡Claro! Aquí va uno:\n\n— ¿Qué le dice una iguana a la otra?  \n— Somos iguana-dos."
    }

    private val httpClient = FakeHttpClient()
    private val model = OpenAIChatModel("gpt-4.1-mini", OpenAIConfig(apiKey = "sk-test"), httpClient)
}
