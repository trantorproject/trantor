package dev.botta.trantor.ai.generation

import dev.botta.json.Json
import dev.botta.trantor.primitives.Cancellation
import dev.botta.trantor.ai.errors.CancelledError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.*
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit.SECONDS

/**
 * The calls of a step run at the same time only when every tool of the step only reads: a model that asks for the
 * weather of two cities waits once instead of twice, and one that also books a trip keeps its order.
 */
class ToolLoopParallelTest {
    @Test
    fun `the calls of a step run at the same time when every tool only reads`() {
        // Each call waits for the other to get there, so it only gets past if they really overlap
        val together = CyclicBarrier(2)
        weather.onExecute = { together.await(5, SECONDS) }
        model.answers(listOf(bariloche, bogota), listOf(TextPart("Listo")))

        val steps = loop().run(ChatRequest("Que temperatura hay en Bariloche y en Bogota?")).steps

        assertThat(steps[0].toolResults.map { it.output })
            .containsExactly(ToolOutput.Text("7 grados en Bariloche"), ToolOutput.Text("7 grados en Bogota"))
        assertThat(weather.threads).doesNotContain(Thread.currentThread())
    }

    @Test
    fun `and their results come back in the order of the calls`() {
        // The second one answers first, and even so the results go back in the order the model asked for them
        val second = CountDownLatch(1)
        weather.onExecute = { city -> if (city == "Bariloche") second.await(5, SECONDS) else second.countDown() }
        model.answers(listOf(bariloche, bogota), listOf(TextPart("Listo")))

        val steps = loop().run(ChatRequest("Que temperatura hay?")).steps

        assertThat(steps[0].toolResults.map { it.callId }).containsExactly("call_1", "call_2")
    }

    @Test
    fun `a tool that can write makes the whole step go in order, on the thread of the run`() {
        model.answers(listOf(bariloche, bookCall), listOf(TextPart("Listo")))

        loop().run(ChatRequest("Que temperatura hay en Bariloche? Reservame un viaje"))

        assertThat(weather.threads.single()).isSameAs(Thread.currentThread())
        assertThat(trip.threads.single()).isSameAs(Thread.currentThread())
    }

    @Test
    fun `and a single call does not need a thread of its own`() {
        model.answers(listOf(bariloche), listOf(TextPart("Listo")))

        loop().run(ChatRequest("Que temperatura hay en Bariloche?"))

        assertThat(weather.threads.single()).isSameAs(Thread.currentThread())
    }

    @Test
    fun `one that fails goes back to the model as an error, and the others answer all the same`() {
        weather.onExecute = { city -> if (city == "Bariloche") throw IllegalStateException("db down") }
        model.answers(listOf(bariloche, bogota), listOf(TextPart("Listo")))

        val step = loop().run(ChatRequest("Que temperatura hay?")).steps[0]

        assertThat(step.toolResults[0].isError).isTrue()
        assertThat(step.toolResults[0].output).isEqualTo(ToolOutput.Text(ToolLoop.GENERIC_FAILURE))
        assertThat(step.toolResults[1].output).isEqualTo(ToolOutput.Text("7 grados en Bogota"))
        assertThat(step.toolFailures.single().toolName).isEqualTo("getWeather")
    }

    @Test
    fun `one that fails the run waits for the others before failing it`() {
        weather.onError = ToolErrorModes.FailRun
        weather.onExecute = { city -> if (city == "Bariloche") throw IllegalStateException("db down") }
        model.answers(listOf(bariloche, bogota))

        assertThatThrownBy { loop().run(ChatRequest("Que temperatura hay?")) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(weather.cities).containsExactlyInAnyOrder("Bariloche", "Bogota")
    }

    @Test
    fun `a cancellation interrupts every tool of the step`() {
        val cancellation = Cancellation()
        val started = CountDownLatch(2)
        val interrupted: MutableList<String> = Collections.synchronizedList(mutableListOf())
        weather.onExecute = { city ->
            started.countDown()

            try {
                CountDownLatch(1).await(5, SECONDS)
            } catch (e: InterruptedException) {
                interrupted.add(city)
                throw e
            }
        }
        model.answers(listOf(bariloche, bogota))
        Thread.ofVirtual().start {
            started.await(5, SECONDS)
            cancellation.cancel()
        }

        val options = CallOptions(cancellation = cancellation)

        assertThatThrownBy { loop().run(ChatRequest("Que temperatura hay?"), options) }
            .isInstanceOf(CancelledError::class.java)
        assertThat(interrupted).containsExactlyInAnyOrder("Bariloche", "Bogota")
        assertThat(model.requests).hasSize(1)
    }

    @Test
    fun `the stream says they all started before any of them finished`() {
        model.streams(listOf(StreamPart.PartDone(bariloche), StreamPart.PartDone(bogota)))
        model.answers(listOf(bariloche, bogota), listOf(TextPart("Listo")))

        val events = loop().stream(ChatRequest("Que temperatura hay?")).use { it.asSequence().toList() }

        assertThat(events.filterIsInstance<RunEvent.ToolStarted>()).hasSize(2)
        assertThat(events.indexOfLast { it is RunEvent.ToolStarted })
            .isLessThan(events.indexOfFirst { it is RunEvent.ToolFinished })
    }

    private fun loop() = ToolLoop(model, listOf(weather, trip), maxSteps = 5)

    private val model = FakeChatModel()
    private val weather = WeatherTool()
    private val trip = TripTool()
    private val bariloche = ToolCallPart("call_1", "getWeather", Json.obj("city" to "Bariloche"))
    private val bogota = ToolCallPart("call_2", "getWeather", Json.obj("city" to "Bogota"))
    private val bookCall = ToolCallPart("call_2", "bookTrip", Json.obj("city" to "Bariloche"))

    /** Only reads, so two calls to it can run at the same time. */
    class WeatherTool: Tool<WeatherTool.Args>(Args.serializer()) {
        override val name = "getWeather"
        override val description = "The current weather of a city"
        override val readOnly = true
        override var onError: ToolErrorModes = ToolErrorModes.SendToModel

        val cities: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val threads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())
        var onExecute: (String) -> Unit = {}

        override fun execute(args: Args, context: ToolContext): ToolResult {
            cities.add(args.city)
            threads.add(Thread.currentThread())
            onExecute(args.city)
            return ToolResult.text("7 grados en ${args.city}")
        }

        @Serializable
        data class Args(val city: String)
    }

    /** Writes, so a step that has it goes in order. */
    class TripTool: Tool<TripTool.Args>(Args.serializer()) {
        override val name = "bookTrip"
        override val description = "Books a trip to a city"

        val threads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())

        override fun execute(args: Args, context: ToolContext): ToolResult {
            threads.add(Thread.currentThread())
            return ToolResult.text("Viaje a ${args.city} reservado")
        }

        @Serializable
        data class Args(val city: String)
    }
}
