@file:Suppress("ClassName")

package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.Json
import dev.botta.trantor.ai.errors.AuthenticationError
import dev.botta.trantor.ai.errors.CancelledError
import dev.botta.trantor.ai.errors.ProviderError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.primitives.Cancellation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** A generation received as it is written, against streams recorded from the real api. */
class AnthropicChatModelStreamTest {
    @Test
    fun `asks the provider to stream`() {
        httpClient.body = fixture("chat/stream-text")

        model.stream(ChatRequest(Message.user("Contame un chiste corto"))).use { it.response() }

        assertThat(sentBody()["stream"]?.asBoolean()).isTrue()
    }

    @Nested
    inner class `a text` {
        @Test
        fun `arrives in deltas as it is written`() {
            httpClient.body = fixture("chat/stream-text")

            val deltas = model.stream(ChatRequest(Message.user("Contame un chiste corto"))).use { stream ->
                stream.asSequence().filterIsInstance<StreamPart.TextDelta>().map { it.text }.toList()
            }

            assertThat(deltas.take(3)).containsExactly("¿", "Por", " qué los")
            assertThat(deltas.joinToString("")).isEqualTo(JOKE)
        }

        @Test
        fun `and whole once its block is finished`() {
            httpClient.body = fixture("chat/stream-text")

            val parts = model.stream(ChatRequest(Message.user("Contame un chiste corto"))).use { stream ->
                stream.asSequence().filterIsInstance<StreamPart.PartDone>().map { it.part }.toList()
            }

            assertThat(parts).containsExactly(TextPart(JOKE))
        }

        @Test
        fun `what the adapter does not map is handed over rather than dropped`() {
            httpClient.body = fixture("chat/stream-text")

            val raw = model.stream(ChatRequest(Message.user("Contame un chiste corto"))).use { stream ->
                stream.asSequence().filterIsInstance<StreamPart.Raw>().map { it.event }.toList()
            }

            assertThat(raw).containsExactly("ping")
        }
    }

    @Nested
    inner class `the response of a stream` {
        @Test
        fun `is the one a call that did not stream would have returned`() {
            httpClient.body = fixture("chat/stream-text")

            val response = model.stream(ChatRequest(Message.user("Contame un chiste corto"))).use { stream ->
                stream.asSequence().toList()
                stream.response()
            }

            assertThat(response.text).isEqualTo(JOKE)
            assertThat(response.finishReason).isEqualTo(FinishReasons.Stop)
            assertThat(response.rawFinishReason).isEqualTo("end_turn")
            assertThat(response.info.id).isEqualTo("msg_011CfGTbL6caBKuW4an5VMkC")
            assertThat(response.info.model).isEqualTo("claude-sonnet-4-5-20250929")
            assertThat(response.warnings).isEmpty()
        }

        @Test
        fun `with the usage of the last event, which counts from the start and not since the one before`() {
            httpClient.body = fixture("chat/stream-text")

            val usage = model.stream(ChatRequest(Message.user("Contame un chiste corto"))).use { it.response() }.usage

            assertThat(usage.inputTokens).isEqualTo(15)
            // The first event says 3, and 28 is the whole answer rather than 3 + 28
            assertThat(usage.outputTokens).isEqualTo(28)
            assertThat(usage.cacheReadTokens).isEqualTo(0)
        }

        @Test
        fun `consumes what is left of the stream when it was not read`() {
            httpClient.body = fixture("chat/stream-text")

            val response = model.stream(ChatRequest(Message.user("Contame un chiste corto"))).use { it.response() }

            assertThat(response.text).isEqualTo(JOKE)
        }

        @Test
        fun `and of one cut before the message ended is what arrived, with a warning and no invented reason`() {
            httpClient.body = fixture("chat/stream-text").substringBefore("event: message_delta")

            val response = model.stream(ChatRequest(Message.user("Contame un chiste corto"))).use { it.response() }

            assertThat(response.text).isEqualTo(JOKE)
            assertThat(response.finishReason).isEqualTo(FinishReasons.Other)
            assertThat(response.rawFinishReason).isNull()
            assertThat(response.warnings.map { it.message })
                .containsExactly("The stream ended before the message was finished")
        }
    }

    @Nested
    inner class `a tool call` {
        @Test
        fun `is put together out of the pieces of json its input arrives in`() {
            httpClient.body = fixture("chat/stream-tools")

            val parts = model.stream(ChatRequest(Message.user("Que temperatura hay en Bariloche?"))).use { stream ->
                stream.asSequence().filterIsInstance<StreamPart.PartDone>().map { it.part }.toList()
            }

            val call = parts.filterIsInstance<ToolCallPart>().single()
            assertThat(call.callId).isEqualTo("toolu_01BfQLk6CEAYm2z98aUCfLZr")
            assertThat(call.toolName).isEqualTo("getWeather")
            assertThat(call.input).isEqualTo(Json.obj("city" to "Bariloche, Argentina"))
            assertThat(call.providerExecuted).isFalse()
        }

        @Test
        fun `and the response says the model is waiting for it`() {
            httpClient.body = fixture("chat/stream-tools")

            val response = model.stream(ChatRequest(Message.user("Que temperatura hay en Bariloche?")))
                .use { it.response() }

            assertThat(response.toolCalls.map { it.callId }).containsExactly("toolu_01BfQLk6CEAYm2z98aUCfLZr")
            assertThat(response.finishReason).isEqualTo(FinishReasons.ToolCalls)
        }
    }

    @Nested
    inner class `thinking` {
        @Test
        fun `arrives in deltas of its own`() {
            httpClient.body = fixture("chat/stream-thinking")

            val deltas = model.stream(ChatRequest(Message.user("Cual es el MCD?"))).use { stream ->
                stream.asSequence().filterIsInstance<StreamPart.ReasoningDelta>().map { it.text }.toList()
            }

            assertThat(deltas.take(2)).containsExactly("Necesito encontrar el máximo", " común divisor (MCD) de")
        }

        @Test
        fun `and whole with its signature, before the text it led to`() {
            httpClient.body = fixture("chat/stream-thinking")

            val response = model.stream(ChatRequest(Message.user("Cual es el MCD?"))).use { it.response() }

            val reasoning = response.content.first() as ReasoningPart
            assertThat(reasoning.text).startsWith("Necesito encontrar el máximo común divisor (MCD) de 1071 y 462.")
            assertThat(reasoning.opaque?.get("signature")?.asString()).startsWith("ErcICpsBCBIYAipA")
            assertThat(response.text).endsWith("El máximo común divisor es **21**.")
        }

        @Test
        fun `so it goes back on the next turn exactly as it came, which is what lets the model go on`() {
            httpClient.body = fixture("chat/stream-thinking")
            val first = model.stream(ChatRequest(Message.user("Cual es el MCD?"))).use { it.response() }
            httpClient.body = fixture("chat/text-simple")

            model.generate(ChatRequest(Message.user("Cual es el MCD?"), first.asMessage(), Message.user("Y el de 12?")))

            val sent = sentBody()["messages"]!!.asArray()!![1].asObject()!!["content"]!!.asArray()!![0].asObject()!!
            assertThat(sent["type"]?.asString()).isEqualTo("thinking")
            assertThat(sent["signature"]).isEqualTo((first.content.first() as ReasoningPart).opaque!!["signature"])
        }

        @Test
        fun `and the tokens it took are a detail of the output`() {
            httpClient.body = fixture("chat/stream-thinking")

            val usage = model.stream(ChatRequest(Message.user("Cual es el MCD?"))).use { it.response() }.usage

            assertThat(usage.outputTokens).isEqualTo(742)
            assertThat(usage.reasoningTokens).isEqualTo(456)
        }
    }

    @Nested
    inner class `a stream that fails` {
        @Test
        fun `with an error event is cut there`() {
            // The event is the example of the Anthropic streaming guide: an error in the middle of a stream is not
            // something that can be asked for, so it cannot be recorded
            httpClient.body = fixture("chat/stream-text").substringBefore("event: content_block_stop") + """
                event: error
                data: {"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}

            """.trimIndent()

            model.stream(ChatRequest(Message.user("Contame un chiste corto"))).use { stream ->
                assertThatThrownBy { stream.asSequence().toList() }
                    .isInstanceOf(ProviderError::class.java)
                    .hasMessageContaining("Overloaded")
            }
        }

        @Test
        fun `with an error status is raised before streaming anything`() {
            httpClient.status = 401
            httpClient.body = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""

            assertThatThrownBy { model.stream(ChatRequest(Message.user("Hola"))) }
                .isInstanceOf(AuthenticationError::class.java)
        }
    }

    @Test
    fun `closing the stream cancels the call`() {
        httpClient.body = fixture("chat/stream-text")

        model.stream(ChatRequest(Message.user("Contame un chiste corto"))).use { it.next() }

        assertThat(httpClient.wasCancelled).isTrue()
        assertThat(httpClient.wasClosed).isTrue()
    }

    @Test
    fun `cancelling while it is read ends the stream as cancelled, not with half an answer`() {
        httpClient.body = fixture("chat/stream-text")
        val cancellation = Cancellation()
        httpClient.whileReading = { cancellation.cancel() }

        val stream = model.stream(ChatRequest(Message.user("Hola")), CallOptions(cancellation = cancellation))

        assertThatThrownBy { stream.use { it.response() } }.isInstanceOf(CancelledError::class.java)
        assertThat(httpClient.wasCancelled).isTrue()
    }

    @Test
    fun `cancelling while it waits for the answer cuts the call and ends it as cancelled`() {
        val cancellation = Cancellation()
        httpClient.whileOpening = { cancellation.cancel() }

        assertThatThrownBy { model.stream(ChatRequest(Message.user("Hola")), CallOptions(cancellation = cancellation)) }
            .isInstanceOf(CancelledError::class.java)
        assertThat(httpClient.options?.cancellation).isSameAs(cancellation)
    }

    private fun sentBody() = Json.parse(httpClient.requestBody!!).asObject()!!

    private fun fixture(name: String) =
        javaClass.getResource("/anthropic/$name.${if ("/stream" in name) "txt" else "json"}")?.readText()
            ?: error("Missing fixture $name")

    companion object {
        private const val JOKE = "¿Por qué los pájaros no usan Facebook?\n\nPorque ya tienen Twitter 🐦"
    }

    private val httpClient = FakeHttpClient()
    private val model = AnthropicChatModel("claude-sonnet-4-5", AnthropicConfig(apiKey = "sk-ant-test"), httpClient)
}
