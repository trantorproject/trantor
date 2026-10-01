@file:Suppress("ClassName")

package dev.botta.trantor.ai.providers.openai

import dev.botta.json.Json
import dev.botta.trantor.ai.errors.*
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.providers.ProviderMetadata
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.primitives.Cancellation
import dev.botta.trantor.web.client.HttpClientError
import dev.botta.trantor.web.client.HttpMethods
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class OpenAIChatModelTest {
    @Test
    fun `posts the request to the responses endpoint`() {
        httpClient.body = fixture("chat/text-simple")

        model.generate(ChatRequest(Message.user("Hola")))

        assertThat(httpClient.method).isEqualTo(HttpMethods.Post)
        assertThat(httpClient.request?.url).isEqualTo("https://api.openai.com/v1/responses")
        assertThat(httpClient.request?.headers).containsEntry("Authorization", "Bearer sk-test")
        assertThat(httpClient.request?.headers).containsEntry("Content-Type", "application/json")
    }

    @Test
    fun `sends the model and the messages as input items`() {
        httpClient.body = fixture("chat/text-simple")

        model.generate(ChatRequest(Message.system("Sos un asistente"), Message.user("Hola")))

        assertThat(sentBody()["model"]?.asString()).isEqualTo("gpt-4.1-mini")
        assertThat(sentBody()["input"].toString()).isEqualTo(
            """[{"type":"message","role":"system","content":"Sos un asistente"},""" +
                """{"type":"message","role":"user","content":[{"type":"input_text","text":"Hola"}]}]"""
        )
    }

    @Test
    fun `sends the dynamic system prompt last, so what comes before it stays cached`() {
        httpClient.body = fixture("chat/text-simple")

        val messages = listOf(Message.system("Sos un asistente"), Message.user("Hola"))

        model.generate(ChatRequest(messages, dynamicSystem = "Hoy es martes"))

        val input = sentBody()["input"]!!.asArray()!!

        assertThat(input.first().toString())
            .isEqualTo("""{"type":"message","role":"system","content":"Sos un asistente"}""")
        assertThat(input.last().toString())
            .isEqualTo("""{"type":"message","role":"system","content":"Hoy es martes"}""")
    }

    @Test
    fun `and a recorded second turn with another dynamic part reads back everything before it`() {
        // Two turns recorded on gpt-5.6-luna with the long system prompt of the cache recordings, and the time as
        // the dynamic part, which changed between them
        httpClient.answers(fixture("system/dynamic-1"), fixture("system/dynamic-2"))
        val luna = OpenAIChatModel("gpt-5.6-luna", OpenAIConfig(apiKey = "sk-test"), httpClient)
        val first = ChatRequest(
            listOf(Message.system("Sos un asistente"), Message.user("Y el 42?")),
            dynamicSystem = "10:00",
        )

        val answer = luna.generate(first)
        val conversation = first.messages + answer.asMessage() + Message.user("Y el 17?")
        val next = luna.generate(first.copy(messages = conversation, dynamicSystem = "11:00"))

        assertThat(sentBody()["input"]!!.asArray()!!.last().toString())
            .isEqualTo("""{"type":"message","role":"system","content":"11:00"}""")
        assertThat(next.usage.cacheReadTokens).isEqualTo(5_139)
        assertThat(next.usage.cacheWriteTokens).isEqualTo(70)
    }

    @Test
    fun `sends an assistant message as output text`() {
        httpClient.body = fixture("chat/text-simple")

        model.generate(ChatRequest(Message.user("Hola"), Message.assistant("Buenas"), Message.user("Todo bien?")))

        assertThat(sentBody()["input"]?.asArray()?.get(1).toString())
            .isEqualTo("""{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Buenas"}]}""")
    }

    @Test
    fun `sends the settings it supports`() {
        httpClient.body = fixture("chat/text-simple")
        val settings = ChatSettings(maxOutputTokens = 100, temperature = 0.2, topP = 0.9)

        model.generate(ChatRequest(listOf(Message.user("Hola")), settings = settings))

        assertThat(sentBody()["max_output_tokens"]?.asInt()).isEqualTo(100)
        assertThat(sentBody()["temperature"]?.asDouble()).isEqualTo(0.2)
        assertThat(sentBody()["top_p"]?.asDouble()).isEqualTo(0.9)
    }

    @Test
    fun `sends a summary of the conversation as something the user tells, where it is`() {
        httpClient.body = fixture("chat/text-simple")

        model.generate(ChatRequest(Message.Summary("Nico viaja a Bariloche en julio"), Message.user("Cuando viajo?")))

        assertThat(sentBody()["input"]?.asArray()?.get(0).toString()).isEqualTo(
            """{"type":"message","role":"user","content":[""" +
                """{"type":"input_text","text":"${Message.Summary.PREAMBLE}"},""" +
                """{"type":"input_text","text":"Nico viaja a Bariloche en julio"}]}""",
        )
    }

    @Test
    fun `leaves out a summary it cannot read, and says so`() {
        httpClient.body = fixture("chat/text-simple")
        val opaque = Message.Summary(null, ProviderMetadata.of("other", Json.obj("encrypted" to "abc")))

        val response = model.generate(ChatRequest(opaque, Message.user("Cuando viajo?")))

        assertThat(sentBody()["input"]?.asArray()).hasSize(1)
        assertThat(response.warnings.map { it.message })
            .containsExactly("A summary with no text to read was left out, so the model does not have it")
    }

    @Test
    fun `warns about a setting it cannot send`() {
        httpClient.body = fixture("chat/text-simple")
        val settings = ChatSettings(seed = 42)

        val response = model.generate(ChatRequest(listOf(Message.user("Hola")), settings = settings))

        assertThat(sentBody().containsKey("seed")).isFalse()
        assertThat(response.warnings.map { it.setting }).containsExactly("seed")
    }

    @Test
    fun `returns the text of the response`() {
        httpClient.body = fixture("chat/text-simple")

        val response = model.generate(ChatRequest(Message.user("Hola")))

        assertThat(response.text).isEqualTo("Hola, ¿en qué puedo ayudarte?")
        assertThat(response.finishReason).isEqualTo(FinishReasons.Stop)
    }

    @Test
    fun `returns the usage with details as subsets`() {
        httpClient.body = fixture("chat/text-simple")

        val usage = model.generate(ChatRequest(Message.user("Hola"))).usage

        assertThat(usage.inputTokens).isEqualTo(18)
        assertThat(usage.outputTokens).isEqualTo(9)
        assertThat(usage.cacheReadTokens).isZero()
        assertThat(usage.cacheWriteTokens).isZero()
        assertThat(usage.reasoningTokens).isZero()
        assertThat(usage.totalTokens).isEqualTo(27)
        assertThat(usage.raw).isNotNull()
    }

    @Test
    fun `counts what went into the cache inside the input, as OpenAI does`() {
        // Recorded on gpt-5.6-luna, one of the models that bill a cache write apart
        httpClient.body = fixture("chat/cache-write")

        val usage = model.generate(ChatRequest(Message.user("Hola"))).usage

        assertThat(usage.inputTokens).isEqualTo(5_142)
        assertThat(usage.cacheWriteTokens).isEqualTo(5_139)
        assertThat(usage.cacheReadTokens).isZero()
        assertThat(usage.uncachedInputTokens).isEqualTo(3)
    }

    @Test
    fun `and what came out of it too, so the three parts add up to the input`() {
        // The same prefix a moment later: most of it is read, and the little that changed is written
        httpClient.body = fixture("chat/cache-read")

        val usage = model.generate(ChatRequest(Message.user("Hola"))).usage

        assertThat(usage.inputTokens).isEqualTo(5_141)
        assertThat(usage.cacheReadTokens).isEqualTo(5_125)
        assertThat(usage.cacheWriteTokens).isEqualTo(13)
        assertThat(usage.uncachedInputTokens).isEqualTo(3)
    }

    @Test
    fun `returns the id and the model that actually answered`() {
        httpClient.body = fixture("chat/text-simple")

        val info = model.generate(ChatRequest(Message.user("Hola"))).info

        assertThat(info.id).isEqualTo("resp_06770bc9d326a2ba006aaf3177bfd887d2bd878f4aee9344fb")
        assertThat(info.model).isEqualTo("gpt-4.1-mini-2025-04-14")
        assertThat(info.provider).isEqualTo("openai")
        assertThat(info.latency.isPositive()).isTrue()
    }

    @Test
    fun `a response cut by max output tokens finishes by length`() {
        httpClient.body = fixture("chat/text-incomplete")

        val response = model.generate(ChatRequest(Message.user("Contame una historia")))

        assertThat(response.finishReason).isEqualTo(FinishReasons.Length)
        assertThat(response.rawFinishReason).isEqualTo("max_output_tokens")
        assertThat(response.text).startsWith("Claro, te cuento la historia de Bariloche.")
        assertThat(response.text).endsWith("San Carlos de Baril")
    }

    @Test
    fun `says which parameter the provider complained about`() {
        httpClient.status = 400
        httpClient.body = fixture("chat/error-400-unsupported-value")

        assertThatThrownBy { model.generate(ChatRequest(Message.user("Hola"))) }
            .isInstanceOfSatisfying(ProviderError::class.java) {
                // The message names the value but not the setting, and 'low' is a valid reasoning effort too
                assertThat(it.parameter).isEqualTo("text.verbosity")
                assertThat(it.code).isEqualTo("unsupported_value")
                assertThat(it.status).isEqualTo(400)
            }
    }

    @Test
    fun `without an api key it says what to set, instead of asking OpenAI`() {
        val model = OpenAIChatModel("gpt-4.1-mini", OpenAIConfig(apiKey = ""), httpClient)

        assertThatThrownBy { model.generate(ChatRequest(Message.user("Hola"))) }
            .isInstanceOf(AuthenticationError::class.java)
            .hasMessageContaining("OPENAI_API_KEY")
            .hasMessageContaining("ai.providers.openai.apiKey")

        assertThat(httpClient.request).isNull()
    }

    @Test
    fun `invalid credentials throw an authentication error`() {
        httpClient.status = 401
        httpClient.body = fixture("chat/error-401")

        assertThatThrownBy { model.generate(ChatRequest(Message.user("Hola"))) }
            .isInstanceOf(AuthenticationError::class.java)
            .hasMessageContaining("Incorrect API key provided")
    }

    @Test
    fun `too many requests throws a rate limit error with its retry after`() {
        httpClient.status = 429
        httpClient.body = """{"error":{"message":"Rate limit reached","type":"requests","code":"rate_limit_exceeded"}}"""
        httpClient.responseHeaders = mapOf("retry-after" to "20")

        assertThatThrownBy { model.generate(ChatRequest(Message.user("Hola"))) }
            .isInstanceOfSatisfying(RateLimitError::class.java) {
                assertThat(it.retryAfter).isEqualTo(20.seconds)
                assertThat(it.retryable).isTrue()
            }
    }

    @Test
    fun `a request over the context window throws a context length error`() {
        httpClient.status = 400
        httpClient.body = """{"error":{"message":"maximum context length is 128000 tokens","code":"context_length_exceeded"}}"""

        assertThatThrownBy { model.generate(ChatRequest(Message.user("Hola"))) }
            .isInstanceOf(ContextLengthExceededError::class.java)
    }

    @Test
    fun `a server error throws a retryable provider unavailable error`() {
        httpClient.status = 503
        httpClient.body = """{"error":{"message":"The server is overloaded","type":"server_error"}}"""

        assertThatThrownBy { model.generate(ChatRequest(Message.user("Hola"))) }
            .isInstanceOfSatisfying(ProviderUnavailableError::class.java) {
                assertThat(it.retryable).isTrue()
                assertThat(it.status).isEqualTo(503)
            }
    }

    @Test
    fun `another error keeps the code of the provider`() {
        httpClient.status = 400
        httpClient.body = """{"error":{"message":"Unknown parameter: 'foo'","code":"unknown_parameter"}}"""

        assertThatThrownBy { model.generate(ChatRequest(Message.user("Hola"))) }
            .isInstanceOfSatisfying(ProviderError::class.java) {
                assertThat(it.code).isEqualTo("unknown_parameter")
                assertThat(it.retryable).isFalse()
            }
    }

    @Test
    fun `a connection failure throws a provider unavailable error`() {
        httpClient.error = HttpClientError("Connection refused")

        assertThatThrownBy { model.generate(ChatRequest(Message.user("Hola"))) }
            .isInstanceOf(ProviderUnavailableError::class.java)
    }

    @Test
    fun `a timeout throws a timeout error`() {
        httpClient.error = HttpClientError("timeout", InterruptedIOException("timeout"))

        assertThatThrownBy { model.generate(ChatRequest(Message.user("Hola"))) }
            .isInstanceOf(TimeoutError::class.java)
    }

    @Test
    fun `sends the timeout of the call as the total timeout of the stream`() {
        httpClient.body = fixture("chat/text-simple")

        model.generate(ChatRequest(Message.user("Hola")), CallOptions(timeout = 30.seconds))

        assertThat(httpClient.options?.totalTimeout).isEqualTo(30_000)
    }

    @Test
    fun `waits for a model that goes silent as long as its config says, whatever the http client says`() {
        httpClient.body = fixture("chat/text-simple")
        val model = OpenAIChatModel("gpt-4.1-mini", OpenAIConfig(apiKey = "sk-test", readTimeout = 300_000), httpClient)

        model.generate(ChatRequest(Message.user("Hola")))

        assertThat(httpClient.options?.readTimeout).isEqualTo(300_000)
    }

    @Test
    fun `and two minutes when the config says nothing`() {
        httpClient.body = fixture("chat/text-simple")

        model.generate(ChatRequest(Message.user("Hola")))

        assertThat(httpClient.options?.readTimeout).isEqualTo(120_000)
    }

    @Test
    fun `sends extra headers of the call`() {
        httpClient.body = fixture("chat/text-simple")

        model.generate(ChatRequest(Message.user("Hola")), CallOptions(headers = mapOf("X-Tenant" to "crafty")))

        assertThat(httpClient.request?.headers).containsEntry("X-Tenant", "crafty")
    }

    @Test
    fun `does not call when it was already cancelled`() {
        val cancellation = Cancellation().apply { cancel() }

        assertThatThrownBy {
            model.generate(ChatRequest(Message.user("Hola")), CallOptions(cancellation = cancellation))
        }
            .isInstanceOf(CancelledError::class.java)
        assertThat(httpClient.request).isNull()
    }

    @Test
    fun `cancelling while it runs ends the call as cancelled`() {
        httpClient.body = fixture("chat/text-simple")
        val cancellation = Cancellation()
        val reading = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        httpClient.whileReading = {
            reading.countDown()
            letGo.await(2, TimeUnit.SECONDS)
        }
        var thrown: Throwable? = null

        val call = Thread.ofVirtual().start {
            thrown = runCatching {
                model.generate(ChatRequest(Message.user("Hola")), CallOptions(cancellation = cancellation))
            }.exceptionOrNull()
        }
        reading.await(2, TimeUnit.SECONDS)
        cancellation.cancel()
        letGo.countDown()
        call.join()

        // Half a body is not an answer, and it is not a parsing error either
        assertThat(thrown).isInstanceOf(CancelledError::class.java)
        assertThat(httpClient.wasCancelled).isTrue()
    }

    @Test
    fun `cancelling while it waits for the answer cuts the call and ends it as cancelled`() {
        val cancellation = Cancellation()
        httpClient.whileOpening = { cancellation.cancel() }

        assertThatThrownBy {
            model.generate(ChatRequest(Message.user("Hola")), CallOptions(cancellation = cancellation))
        }.isInstanceOf(CancelledError::class.java)
        assertThat(httpClient.options?.cancellation).isSameAs(cancellation)
    }

    @Test
    fun `lets go of the cancellation once the call is over`() {
        httpClient.body = fixture("chat/text-simple")
        val cancellation = Cancellation()

        repeat(3) { model.generate(ChatRequest(Message.user("Hola")), CallOptions(cancellation = cancellation)) }
        cancellation.cancel()

        // Nothing left listening: a token used for many calls does not keep a callback for each one
        assertThat(httpClient.wasCancelled).isFalse()
    }

    @Test
    fun `closes the response`() {
        httpClient.body = fixture("chat/text-simple")

        model.generate(ChatRequest(Message.user("Hola")))

        assertThat(httpClient.wasClosed).isTrue()
    }

    private fun sentBody() = Json.parse(httpClient.requestBody!!).asObject()!!

    private fun fixture(name: String) =
        javaClass.getResource("/openai/$name.json")?.readText() ?: error("Missing fixture $name")

    private val httpClient = FakeHttpClient()
    private val model = OpenAIChatModel("gpt-4.1-mini", OpenAIConfig(apiKey = "sk-test"), httpClient)
}
