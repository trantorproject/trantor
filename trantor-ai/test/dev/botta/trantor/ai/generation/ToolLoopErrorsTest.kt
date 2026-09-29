@file:Suppress("ClassName")

package dev.botta.trantor.ai.generation

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.errors.CancelledError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.*
import dev.botta.trantor.primitives.Cancellation
import dev.botta.trantor.web.client.HttpClientError
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * What the model and the application get when a tool fails. By default the model learns that the call failed but
 * not why, since it could end up telling the user; the exception stays in the step for the application.
 */
class ToolLoopErrorsTest {
    @Nested
    inner class `mistakes of the model, which it can fix` {
        @Test
        fun `args that do not fit go back naming the field, and the run goes on`() {
            model.answers(listOf(call("getWeather", Json.obj("town" to "Bariloche"))), listOf(TextPart("Perdon")))

            val steps = loop().run(ChatRequest("Hola")).steps

            val result = steps[0].toolResults.single()
            assertThat(result.isError).isTrue()
            assertThat(text(result)).contains("city")
            assertThat(steps).hasSize(2)
            assertThat(steps[0].toolFailures.single().error).isInstanceOf(InvalidToolInputError::class.java)
        }

        @Test
        fun `and so do args their own type rejects`() {
            model.answers(listOf(call("getWeather", Json.obj("city" to ""))))

            val steps = loop().run(ChatRequest("Hola")).steps

            assertThat(text(steps[0].toolResults.single())).contains("The city cannot be blank")
        }

        @Test
        fun `a tool that does not exist goes back with the ones that do`() {
            model.answers(listOf(call("getWheather", Json.obj("city" to "Bariloche"))))

            val steps = loop().run(ChatRequest("Hola")).steps

            val result = steps[0].toolResults.single()
            assertThat(result.isError).isTrue()
            assertThat(text(result))
                .isEqualTo("There is no tool called getWheather. The tools are: getWeather, getTime")
        }
    }

    @Nested
    inner class `failures of the tool` {
        @Test
        fun `a ToolError is feedback for the model, so its message goes`() {
            weather.failWith = ToolError("There is no weather for Atlantis, ask for a real city")
            model.answers(listOf(weatherCall()))

            val steps = loop().run(ChatRequest("Hola")).steps

            assertThat(text(steps[0].toolResults.single()))
                .isEqualTo("There is no weather for Atlantis, ask for a real city")
        }

        @Test
        fun `any other exception reaches the model only as a failure, and the application gets it in the step`() {
            val failure = IllegalStateException("connection refused to weather-db:5432")
            weather.failWith = failure
            model.answers(listOf(weatherCall()), listOf(TextPart("No pude")))

            val steps = loop().run(ChatRequest("Hola")).steps

            val result = steps[0].toolResults.single()
            assertThat(text(result)).isEqualTo("Tool execution failed")
            assertThat(result.isError).isTrue()
            assertThat(steps[0].toolFailures.single()).isEqualTo(ToolFailure("call_1", "getWeather", failure))
            assertThat(steps).hasSize(2)
        }

        @Test
        fun `a handler of the application can say something safe instead`() {
            weather.failWith = NotFound("city 42")
            val handler = ToolErrorHandler { error, _ -> if (error is NotFound) "It does not exist" else null }
            model.answers(listOf(weatherCall()))

            val steps = loop(handlers = listOf(handler)).run(ChatRequest("Hola")).steps

            assertThat(text(steps[0].toolResults.single())).isEqualTo("It does not exist")
            assertThat(steps[0].toolFailures.single().error).isSameAs(weather.failWith)
        }

        @Test
        fun `the first handler that answers wins, and one that does not know the error lets it pass`() {
            weather.failWith = NotFound("city 42")
            val unrelated = ToolErrorHandler { _, _ -> null }
            val first = ToolErrorHandler { _, call -> "first, for ${call.toolName}" }
            val second = ToolErrorHandler { _, _ -> "second" }
            model.answers(listOf(weatherCall()))

            val steps = loop(handlers = listOf(unrelated, first, second)).run(ChatRequest("Hola")).steps

            assertThat(text(steps[0].toolResults.single())).isEqualTo("first, for getWeather")
        }

        @Test
        fun `with no handler that knows it, the generic message`() {
            weather.failWith = NotFound("city 42")
            model.answers(listOf(weatherCall()))

            val handler = ToolErrorHandler { _, _ -> null }

            val steps = loop(handlers = listOf(handler)).run(ChatRequest("Hola")).steps

            assertThat(text(steps[0].toolResults.single())).isEqualTo("Tool execution failed")
        }

        @Test
        fun `a call that fails does not keep the others of the step from running`() {
            weather.failWith = IllegalStateException("boom")
            model.answers(listOf(weatherCall(), call("getTime", Json.obj(), "call_2")))

            val steps = loop().run(ChatRequest("Hola")).steps

            assertThat(steps[0].toolResults.map { it.isError }).containsExactly(true, false)
            assertThat(time.calls).isEqualTo(1)
        }

        @Test
        fun `a tool that asks to fail the run fails it with its own exception`() {
            val failure = IllegalStateException("the ledger is inconsistent")
            val ledger = LedgerTool(failure)
            model.answers(listOf(call("postEntry", Json.obj())))

            assertThatThrownBy { ToolLoop(model, listOf(ledger)).run(ChatRequest("Hola")) }.isSameAs(failure)
        }
    }

    @Nested
    inner class cancellation {
        @Test
        fun `a tool that was cancelled ends the run`() {
            weather.failWith = CancelledError()
            model.answers(listOf(weatherCall()))

            assertThatThrownBy { loop().run(ChatRequest("Hola")) }.isInstanceOf(CancelledError::class.java)
            assertThat(model.requests).hasSize(1)
        }

        @Test
        fun `and so does one that was interrupted, which keeps the thread interrupted`() {
            weather.failWith = InterruptedException()
            model.answers(listOf(weatherCall()))

            try {
                assertThatThrownBy { loop().run(ChatRequest("Hola")) }.isInstanceOf(CancelledError::class.java)
                assertThat(Thread.currentThread().isInterrupted).isTrue()
            } finally {
                Thread.interrupted()
            }
        }

        @Test
        fun `a tool whose call the cancellation of the run cut ends it as cancelled, not as a failure of the tool`() {
            val consulted = mutableListOf<Throwable>()
            val handler = ToolErrorHandler { error, _ -> consulted += error; null }
            model.answers(listOf(call("download", Json.obj())))

            val loop = ToolLoop(model, listOf(DownloadTool(cancellation)), errorHandlers = listOf(handler))

            assertThatThrownBy { loop.run(ChatRequest("Hola"), CallOptions(cancellation = cancellation)) }
                .isInstanceOf(CancelledError::class.java)
            assertThat(consulted).isEmpty()
        }

        @Test
        fun `and so does one that asks to fail the run, which did not fail but was cancelled`() {
            model.answers(listOf(call("download", Json.obj())))

            val loop = ToolLoop(model, listOf(DownloadTool(cancellation, ToolErrorModes.FailRun)))

            assertThatThrownBy { loop.run(ChatRequest("Hola"), CallOptions(cancellation = cancellation)) }
                .isInstanceOf(CancelledError::class.java)
        }

        private val cancellation = Cancellation()
    }

    private fun loop(handlers: List<ToolErrorHandler> = emptyList()) =
        ToolLoop(model, listOf(weather, time), errorHandlers = handlers)

    private fun weatherCall() = call("getWeather", Json.obj("city" to "Bariloche"))

    private fun call(name: String, input: JsonObject, callId: String = "call_1") = ToolCallPart(callId, name, input)

    private fun text(result: ToolResultPart) = (result.output as ToolOutput.Text).value

    private val model = FakeChatModel()
    private val weather = WeatherTool()
    private val time = TimeTool()

    class NotFound(message: String): Exception(message)

    class WeatherTool: Tool<WeatherTool.Args>(Args.serializer()) {
        override val name = "getWeather"
        override val description = "The current weather of a city"

        var failWith: Exception? = null

        override fun execute(args: Args, context: ToolContext): ToolResult {
            failWith?.let { throw it }
            return ToolResult.text("7 grados")
        }

        @Serializable
        data class Args(val city: String) {
            init {
                require(city.isNotBlank()) { "The city cannot be blank" }
            }
        }
    }

    class TimeTool: Tool<TimeTool.Args>(Args.serializer()) {
        override val name = "getTime"
        override val description = "The time"

        var calls = 0

        override fun execute(args: Args, context: ToolContext): ToolResult {
            calls++
            return ToolResult.text("12:00")
        }

        @Serializable
        class Args
    }

    /** Cancels the run while it downloads, and fails as the HTTP client of Trantor does when a call is cut. */
    class DownloadTool(
        private val cancellation: Cancellation,
        override val onError: ToolErrorModes = ToolErrorModes.SendToModel,
    ): Tool<DownloadTool.Args>(Args.serializer()) {
        override val name = "download"
        override val description = "Downloads a file"

        override fun execute(args: Args, context: ToolContext): ToolResult {
            cancellation.cancel()
            throw HttpClientError("Canceled")
        }

        @Serializable
        class Args
    }

    class LedgerTool(private val failure: Exception): Tool<LedgerTool.Args>(Args.serializer()) {
        override val name = "postEntry"
        override val description = "Posts an entry to the ledger"
        override val onError = ToolErrorModes.FailRun

        override fun execute(args: Args, context: ToolContext): ToolResult = throw failure

        @Serializable
        class Args
    }
}
