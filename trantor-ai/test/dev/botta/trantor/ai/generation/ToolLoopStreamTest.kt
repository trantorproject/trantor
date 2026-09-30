@file:Suppress("ClassName")

package dev.botta.trantor.ai.generation

import dev.botta.json.Json
import dev.botta.trantor.primitives.Cancellation
import dev.botta.trantor.ai.errors.CancelledError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.*
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The same loop, received as it happens. What it says about the tools is what a UI shows while it waits. */
class ToolLoopStreamTest {
    @Test
    fun `a run without tools is a step with the deltas of the model`() {
        model.streams(listOf(StreamPart.TextDelta("Ho"), StreamPart.TextDelta("la")))

        val events = loop().stream(ChatRequest("Saludá")).use { it.asSequence().toList() }

        assertThat(events).containsExactly(
            RunEvent.StepStarted(1),
            RunEvent.Model(StreamPart.TextDelta("Ho")),
            RunEvent.Model(StreamPart.TextDelta("la")),
            RunEvent.StepFinished(1),
        )
    }

    @Test
    fun `the text as it arrives, for whoever only wants to print it`() {
        model.streams(
            listOf(StreamPart.TextDelta("Ho"), StreamPart.ReasoningDelta("pienso"), StreamPart.TextDelta("la")),
        )

        val text = loop().stream(ChatRequest("Saludá")).use { it.textDeltas().joinToString("") }

        assertThat(text).isEqualTo("Hola")
    }

    @Test
    fun `with tools, it says when each one runs and starts the next step`() {
        model.streams(listOf(StreamPart.PartDone(weatherCall)), listOf(StreamPart.TextDelta("7 grados")))
        model.answers(listOf(weatherCall), listOf(TextPart("7 grados")))

        val events = loop().stream(ChatRequest("Que temperatura hay?")).use { it.asSequence().toList() }

        assertThat(events).containsExactly(
            RunEvent.StepStarted(1),
            RunEvent.Model(StreamPart.PartDone(weatherCall)),
            RunEvent.ToolStarted(weatherCall),
            RunEvent.ToolFinished(result("7 grados en Bariloche")),
            RunEvent.StepFinished(1),
            RunEvent.StepStarted(2),
            RunEvent.Model(StreamPart.TextDelta("7 grados")),
            RunEvent.StepFinished(2),
        )
    }

    @Test
    fun `and the result has both steps, with the usage and the messages of the whole run`() {
        model.usage = Usage(inputTokens = 10, outputTokens = 5)
        model.streams(listOf(StreamPart.PartDone(weatherCall)), listOf(StreamPart.TextDelta("7 grados")))
        model.answers(listOf(weatherCall), listOf(TextPart("7 grados")))

        val result = loop().stream(ChatRequest("Que temperatura hay?")).use { stream ->
            stream.forEach { }
            stream.result()
        }

        assertThat(result.steps).hasSize(2)
        assertThat(result.text).isEqualTo("7 grados")
        assertThat(result.usage).isEqualTo(Usage(inputTokens = 20, outputTokens = 10))
        assertThat(result.newMessages).hasSize(3)
    }

    @Test
    fun `the result of a stream nobody read consumes what is left`() {
        model.streams(listOf(StreamPart.PartDone(weatherCall)), listOf(StreamPart.TextDelta("7 grados")))
        model.answers(listOf(weatherCall), listOf(TextPart("7 grados")))

        val result = loop().stream(ChatRequest("Que temperatura hay?")).use { it.result() }

        assertThat(result.text).isEqualTo("7 grados")
        assertThat(weather.cities).containsExactly("Bariloche")
    }

    @Test
    fun `closing it halfway closes the call and runs no tool`() {
        model.streams(listOf(StreamPart.TextDelta("Bus"), StreamPart.PartDone(weatherCall)))
        model.answers(listOf(weatherCall))

        val stream = loop().stream(ChatRequest("Que temperatura hay?"))
        stream.next()
        stream.next()
        stream.close()

        assertThat(stream.hasNext()).isFalse()
        assertThat(model.streamsClosed).isEqualTo(1)
        assertThat(weather.cities).isEmpty()
    }

    @Test
    fun `a tool that fails is finished with its failure, and the model hears about it`() {
        weather.failWith = IllegalStateException("db down")
        model.streams(listOf(StreamPart.PartDone(weatherCall)), listOf(StreamPart.TextDelta("No pude")))
        model.answers(listOf(weatherCall), listOf(TextPart("No pude")))

        val events = loop().stream(ChatRequest("Que temperatura hay?")).use { it.asSequence().toList() }

        val finished = events.filterIsInstance<RunEvent.ToolFinished>().single()
        assertThat(finished.result.isError).isTrue()
        assertThat(finished.failure?.error).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `past the steps allowed it fails, with what it did so far`() {
        model.streams(listOf(StreamPart.PartDone(weatherCall)), listOf(StreamPart.PartDone(weatherCall)))
        model.answers(listOf(weatherCall), listOf(weatherCall))

        val stream = loop(maxSteps = 2).stream(ChatRequest("Que temperatura hay?"))

        assertThatThrownBy { stream.use { it.forEach { } } }
            .isInstanceOfSatisfying(MaxStepsExceededError::class.java) {
                assertThat(it.result.steps).hasSize(2)
            }
        assertThat(weather.cities).containsExactly("Bariloche")
    }

    @Test
    fun `a cancellation stops it before the next step`() {
        val cancellation = Cancellation()
        weather.onExecute = { cancellation.cancel() }
        model.streams(listOf(StreamPart.PartDone(weatherCall)))
        model.answers(listOf(weatherCall))

        val stream = loop().stream(ChatRequest("Que temperatura hay?"), CallOptions(cancellation = cancellation))

        assertThatThrownBy { stream.use { it.forEach { } } }.isInstanceOf(CancelledError::class.java)
        assertThat(model.requests).hasSize(1)
    }

    private fun loop(maxSteps: Int = 5) = ToolLoop(model, listOf(weather), maxSteps)

    private fun result(text: String) = ToolResultPart("call_1", "getWeather", ToolOutput.Text(text))

    private val model = FakeChatModel()
    private val weather = WeatherTool()
    private val weatherCall = ToolCallPart("call_1", "getWeather", Json.obj("city" to "Bariloche"))

    class WeatherTool: Tool<WeatherTool.Args>() {
        override val name = "getWeather"
        override val description = "The current weather of a city"

        val cities = mutableListOf<String>()
        var failWith: Exception? = null
        var onExecute: () -> Unit = {}

        override fun execute(args: Args, context: ToolContext): ToolResult {
            failWith?.let { throw it }
            cities.add(args.city)
            onExecute()
            return ToolResult.text("7 grados en ${args.city}")
        }

        @Serializable
        data class Args(val city: String)
    }
}
