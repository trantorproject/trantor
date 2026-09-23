package dev.botta.trantor.ai.providers.anthropic

import dev.botta.json.Json
import dev.botta.trantor.ai.Cancellation
import dev.botta.trantor.ai.errors.*
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.providers.ProviderOptions
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.web.client.HttpClientError
import dev.botta.trantor.web.client.HttpMethods
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * What the adapter sends. What it reads back is tested against recorded answers: asserting here on a body written
 * by hand would only prove that two guesses about the api agree with each other.
 */
class AnthropicChatModelTest {
    @Test
    fun `posts the request to the messages endpoint`() {
        model.generate(ChatRequest(Message.user("Hola")))

        assertThat(httpClient.method).isEqualTo(HttpMethods.Post)
        assertThat(httpClient.request?.url).isEqualTo("https://api.anthropic.com/v1/messages")
        assertThat(httpClient.request?.headers).containsEntry("Content-Type", "application/json")
    }

    @Test
    fun `takes the api key as a header of its own, and pins the version of the api`() {
        model.generate(ChatRequest(Message.user("Hola")))

        assertThat(httpClient.request?.headers).containsEntry("x-api-key", "sk-ant-test")
        assertThat(httpClient.request?.headers).containsEntry("anthropic-version", AnthropicConfig.DEFAULT_VERSION)
        // No bearer: sending the key that way is how it ends up ignored and the call answered with a 401
        assertThat(httpClient.request?.headers).doesNotContainKey("Authorization")
    }

    @Test
    fun `asks for the betas the configuration named, and for none when it named none`() {
        modelWith(AnthropicConfig(apiKey = "sk-ant-test", betas = listOf("one", "two")))
            .generate(ChatRequest(Message.user("Hola")))

        assertThat(httpClient.request?.headers).containsEntry("anthropic-beta", "one,two")

        model.generate(ChatRequest(Message.user("Hola")))

        assertThat(httpClient.request?.headers).doesNotContainKey("anthropic-beta")
    }

    @Test
    fun `sends the model and the messages as content blocks`() {
        model.generate(ChatRequest(Message.user("Hola")))

        assertThat(sentBody()["model"]?.asString()).isEqualTo("claude-sonnet-4-5")
        assertThat(sentBody()["messages"].toString())
            .isEqualTo("""[{"role":"user","content":[{"type":"text","text":"Hola"}]}]""")
    }

    @Test
    fun `sends the system prompt as a field and not as a message`() {
        model.generate(ChatRequest(Message.system("Sos un asistente"), Message.user("Hola")))

        assertThat(sentBody()["system"]?.asString()).isEqualTo("Sos un asistente")
        assertThat(sentBody()["messages"]?.asArray()?.size).isEqualTo(1)
    }

    @Test
    fun `joins several system prompts instead of losing all but one, and says that it did`() {
        val response = model.generate(
            ChatRequest(Message.system("Sos un asistente"), Message.user("Hola"), Message.system("Contesta corto")),
        )

        assertThat(sentBody()["system"]?.asString()).isEqualTo("Sos un asistente\n\nContesta corto")
        assertThat(response.warnings.map { it.message }).containsExactly(
            "claude-sonnet-4-5 does not take system messages in the middle of the conversation, " +
                "so the 2 of them were joined into the system prompt",
        )
    }

    @Test
    fun `leaves out the system field when nobody wrote one`() {
        model.generate(ChatRequest(Message.user("Hola")))

        assertThat(sentBody().containsKey("system")).isFalse()
    }

    @Test
    fun `merges two messages of the same role in a row, because Anthropic wants them to alternate`() {
        model.generate(ChatRequest(Message.user("Hola"), Message.user("Te hago una pregunta")))

        assertThat(sentBody()["messages"].toString()).isEqualTo(
            """[{"role":"user","content":[{"type":"text","text":"Hola"},""" +
                """{"type":"text","text":"Te hago una pregunta"}]}]""",
        )
    }

    @Test
    fun `keeps the turns apart when the roles do alternate`() {
        model.generate(ChatRequest(Message.user("Hola"), Message.assistant("Buenas"), Message.user("Todo bien?")))

        assertThat(sentBody()["messages"]?.asArray()?.size).isEqualTo(3)
        assertThat(sentBody()["messages"]?.asArray()?.get(1).toString())
            .isEqualTo("""{"role":"assistant","content":[{"type":"text","text":"Buenas"}]}""")
    }

    @Test
    fun `always sends max tokens, which the api requires, and asks for all the model gives`() {
        model.generate(ChatRequest(Message.user("Hola")))

        assertThat(sentBody()["max_tokens"]?.asInt()).isEqualTo(64_000)
    }

    @Test
    fun `and the one the call asked for wins over the default of the configuration`() {
        modelWith(AnthropicConfig(apiKey = "sk-ant-test", defaultMaxTokens = 100))
            .generate(ChatRequest(listOf(Message.user("Hola")), settings = ChatSettings(maxOutputTokens = 7)))

        assertThat(sentBody()["max_tokens"]?.asInt()).isEqualTo(7)
    }

    @Test
    fun `sends the settings it supports`() {
        val settings = ChatSettings(temperature = 0.2, topP = 0.9, stopSequences = listOf("FIN"))

        model.generate(ChatRequest(listOf(Message.user("Hola")), settings = settings))

        assertThat(sentBody()["temperature"]?.asDouble()).isEqualTo(0.2)
        assertThat(sentBody()["top_p"]?.asDouble()).isEqualTo(0.9)
        assertThat(sentBody()["stop_sequences"].toString()).isEqualTo("""["FIN"]""")
    }

    @Test
    fun `warns about a setting it cannot send`() {
        val settings = ChatSettings(seed = 42)

        val response = model.generate(ChatRequest(listOf(Message.user("Hola")), settings = settings))

        assertThat(sentBody().containsKey("seed")).isFalse()
        assertThat(response.warnings.map { it.setting }).containsExactly("seed")
    }

    @Test
    fun `fails instead of warning when the call asked it to`() {
        val settings = ChatSettings(seed = 42, failOnWarnings = true)

        assertThatThrownBy { model.generate(ChatRequest(listOf(Message.user("Hola")), settings = settings)) }
            .isInstanceOf(UnsupportedRequestError::class.java)
    }

    @Test
    fun `several options of its own add up in order, and what a later one sets wins`() {
        // An agent brings its options and the run its own after them
        val options = ProviderOptions.of(
            AnthropicOptions(userId = "del-agente", serviceTier = ServiceTiers.StandardOnly),
            AnthropicOptions(userId = "del-run"),
        )

        model.generate(ChatRequest(listOf(Message.user("Hola")), providerOptions = options))

        assertThat(sentBody()["metadata"].toString()).isEqualTo("""{"user_id":"del-run"}""")
        assertThat(sentBody()["service_tier"]?.asString()).isEqualTo("standard_only")
    }

    @Test
    fun `does not stream unless it was asked to`() {
        model.generate(ChatRequest(Message.user("Hola")))

        assertThat(sentBody().containsKey("stream")).isFalse()
    }

    @Test
    fun `without an api key it says what to set, instead of asking Anthropic`() {
        val model = AnthropicChatModel("claude-sonnet-4-5", AnthropicConfig(apiKey = ""), httpClient)

        assertThatThrownBy { model.generate(ChatRequest(Message.user("Hola"))) }
            .isInstanceOf(AuthenticationError::class.java)
            .hasMessageContaining("ANTHROPIC_API_KEY")
            .hasMessageContaining("ai.providers.anthropic.apiKey")

        assertThat(httpClient.request).isNull()
    }

    @Test
    fun `invalid credentials throw an authentication error`() {
        httpClient.status = 401
        httpClient.body = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""

        assertThatThrownBy { model.generate(ChatRequest(Message.user("Hola"))) }
            .isInstanceOfSatisfying(AuthenticationError::class.java) {
                assertThat(it).hasMessageContaining("invalid x-api-key")
                assertThat(it.code).isEqualTo("authentication_error")
            }
    }

    @Test
    fun `too many requests throws a rate limit error with its retry after`() {
        httpClient.status = 429
        httpClient.body = """{"type":"error","error":{"type":"rate_limit_error","message":"Number of requests"}}"""
        httpClient.responseHeaders = mapOf("retry-after" to "20")

        assertThatThrownBy { model.generate(ChatRequest(Message.user("Hola"))) }
            .isInstanceOfSatisfying(RateLimitError::class.java) {
                assertThat(it.retryAfter).isEqualTo(20.seconds)
                assertThat(it.retryable).isTrue()
            }
    }

    @Test
    fun `a conversation that does not fit throws a context length error, though it comes as a bad request`() {
        httpClient.status = 400
        httpClient.body = """{"type":"error","error":{"type":"invalid_request_error",""" +
            """"message":"prompt is too long: 250000 tokens > 200000 maximum"}}"""

        assertThatThrownBy { model.generate(ChatRequest(Message.user("Hola"))) }
            .isInstanceOf(ContextLengthExceededError::class.java)
    }

    @Test
    fun `and so does a request too large to be read at all`() {
        httpClient.status = 413
        httpClient.body = """{"type":"error","error":{"type":"request_too_large","message":"Request body too large"}}"""

        assertThatThrownBy { model.generate(ChatRequest(Message.user("Hola"))) }
            .isInstanceOf(ContextLengthExceededError::class.java)
    }

    @Test
    fun `being overloaded throws a retryable provider unavailable error`() {
        httpClient.status = 529
        httpClient.body = """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""

        assertThatThrownBy { model.generate(ChatRequest(Message.user("Hola"))) }
            .isInstanceOfSatisfying(ProviderUnavailableError::class.java) {
                assertThat(it.retryable).isTrue()
                assertThat(it.status).isEqualTo(529)
            }
    }

    @Test
    fun `another error keeps the type of the provider as its code`() {
        httpClient.status = 400
        httpClient.body = """{"type":"error","error":{"type":"invalid_request_error","message":"tools.0: extra field"}}"""

        assertThatThrownBy { model.generate(ChatRequest(Message.user("Hola"))) }
            .isInstanceOfSatisfying(ProviderError::class.java) {
                assertThat(it.code).isEqualTo("invalid_request_error")
                assertThat(it.retryable).isFalse()
            }
    }

    @Test
    fun `an error that is not json still says what came back`() {
        httpClient.status = 502
        httpClient.body = "<html>Bad gateway</html>"

        assertThatThrownBy { model.generate(ChatRequest(Message.user("Hola"))) }
            .isInstanceOf(ProviderUnavailableError::class.java)
            .hasMessageContaining("Bad gateway")
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
        model.generate(ChatRequest(Message.user("Hola")), CallOptions(timeout = 30.seconds))

        assertThat(httpClient.options?.totalTimeout).isEqualTo(30_000)
    }

    @Test
    fun `sends extra headers of the call`() {
        model.generate(ChatRequest(Message.user("Hola")), CallOptions(headers = mapOf("X-Tenant" to "crafty")))

        assertThat(httpClient.request?.headers).containsEntry("X-Tenant", "crafty")
    }

    @Test
    fun `does not call when it was already cancelled`() {
        val cancellation = Cancellation().apply { cancel() }

        assertThatThrownBy {
            model.generate(ChatRequest(Message.user("Hola")), CallOptions(cancellation = cancellation))
        }.isInstanceOf(CancelledError::class.java)
        assertThat(httpClient.request).isNull()
    }

    @Test
    fun `cancelling while it runs ends the call as cancelled`() {
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
    fun `closes the response`() {
        model.generate(ChatRequest(Message.user("Hola")))

        assertThat(httpClient.wasClosed).isTrue()
    }

    private fun sentBody() = Json.parse(httpClient.requestBody!!).asObject()!!

    private fun modelWith(config: AnthropicConfig) = AnthropicChatModel("claude-sonnet-4-5", config, httpClient)

    // An empty object is an answer the mapper reads without complaining, so these tests are about what was sent
    private val httpClient = FakeHttpClient()
    private val model = modelWith(AnthropicConfig(apiKey = "sk-ant-test"))
}
