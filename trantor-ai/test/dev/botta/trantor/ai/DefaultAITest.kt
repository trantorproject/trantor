@file:Suppress("ClassName")

package dev.botta.trantor.ai

import dev.botta.json.Json
import dev.botta.trantor.ai.errors.NoObjectGeneratedError
import dev.botta.trantor.ai.generation.MaxStepsExceededError
import dev.botta.trantor.ai.generation.RunEvent
import dev.botta.trantor.ai.generation.textDeltas
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.providers.AIProvider
import dev.botta.trantor.ai.providers.RawOptions
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.*
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class DefaultAITest {
    @Nested
    inner class text {
        @Test
        fun `asks the default model and gives back the text`() {
            model.answers(listOf(TextPart("Hola!")))

            val text = ai.text("Saludá")

            assertThat(text).isEqualTo("Hola!")
            assertThat(provider.asked).containsExactly("default-model")
            assertThat(model.request?.messages).containsExactly(Message.user("Saludá"))
        }

        @Test
        fun `or the model it is told, by alias or by reference`() {
            ai.text("Saludá", model = "fast")
            ai.text("Saludá", model = "fake/other-model")

            assertThat(provider.asked).containsExactly("fast-model", "other-model")
        }

        @Test
        fun `with the options of the call`() {
            val options = CallOptions(timeout = 5.seconds)

            ai.text("Saludá", options = options)

            assertThat(model.options).isSameAs(options)
        }
    }

    @Nested
    inner class generate {
        @Test
        fun `sends the messages in the order they were written`() {
            val history = listOf(Message.user("Hola"), Message.assistant("Hola, en que te ayudo?"))

            ai.generate {
                system("Sos el asistente de una ferreteria")
                messages(history)
                user("Tienen taladros?")
            }

            assertThat(model.request?.messages).containsExactly(
                Message.system("Sos el asistente de una ferreteria"),
                Message.user("Hola"),
                Message.assistant("Hola, en que te ayudo?"),
                Message.user("Tienen taladros?"),
            )
        }

        @Test
        fun `the dynamic system prompt goes apart from the messages`() {
            ai.generate {
                system("Sos el asistente de una ferreteria")
                dynamicSystem("Hoy es martes")
                user("Tienen taladros?")
            }

            assertThat(model.request?.dynamicSystem).isEqualTo("Hoy es martes")
            assertThat(model.request?.messages).hasSize(2)
        }

        @Test
        fun `runs the tools until the model answers`() {
            model.answers(listOf(weatherCall), listOf(TextPart("Hacen 7 grados")))

            val result = ai.generate {
                user("Que temperatura hay en Bariloche?")
                tools(weather)
            }

            assertThat(result.text).isEqualTo("Hacen 7 grados")
            assertThat(result.steps).hasSize(2)
            assertThat(weather.cities).containsExactly("Bariloche")
        }

        @Test
        fun `stops at the steps it is given`() {
            model.answers(listOf(weatherCall), listOf(weatherCall))

            assertThatThrownBy {
                ai.generate {
                    user("Que temperatura hay?")
                    tools(weather)
                    maxSteps(2)
                }
            }.isInstanceOf(MaxStepsExceededError::class.java)
        }

        @Test
        fun `the settings, the tool choice and the options of the provider reach the model`() {
            val raw = RawOptions("openai", Json.obj("store" to false))

            ai.generate {
                user("Hola")
                tools(weather)
                toolChoice(ToolChoice.Required)
                settings { temperature = 0.2 }
                options(raw)
            }

            assertThat(model.request?.settings?.temperature).isEqualTo(0.2)
            assertThat(model.request?.toolChoice).isEqualTo(ToolChoice.Required)
            assertThat(model.request?.providerOptions?.forProvider("openai")).containsExactly(raw)
        }

        @Test
        fun `the tools get the context of the run`() {
            model.answers(listOf(weatherCall))

            ai.generate {
                user("Que temperatura hay en Bariloche?")
                tools(weather)
                context(RunContext("acme"))
            }

            assertThat(weather.context?.run?.get<String>()).isEqualTo("acme")
        }

        @Test
        fun `a failing tool goes through the error handlers of the application`() {
            weather.failWith = IllegalStateException("db down")
            val ai = DefaultAI(registry, ToolErrorHandlers().add { _, _ -> "El clima no esta disponible" })
            model.answers(listOf(weatherCall))

            val result = ai.generate {
                user("Que temperatura hay en Bariloche?")
                tools(weather)
            }

            val output = result.steps[0].toolResults.single().output
            assertThat(output).isEqualTo(ToolOutput.Text("El clima no esta disponible"))
        }

        @Test
        fun `the call options reach every step`() {
            val options = CallOptions(timeout = 5.seconds)
            model.answers(listOf(weatherCall))

            ai.generate {
                user("Que temperatura hay en Bariloche?")
                tools(weather)
                callOptions(options)
            }

            assertThat(model.requests).hasSize(2)
            assertThat(model.options).isSameAs(options)
        }
    }

    @Nested
    inner class `an object` {
        @Test
        fun `asks for the schema of the type and reads the answer as it`() {
            model.answers(listOf(TextPart("""{"city":"Bariloche","celsius":7}""")))

            val weather = ai.generate<Weather> { user("Que temperatura hay en Bariloche?") }

            assertThat(weather).isEqualTo(Weather("Bariloche", 7))
            val output = model.request?.output as OutputSpec.Json
            assertThat(output.schema.path("properties.celsius.type")?.asString()).isEqualTo("integer")
        }

        @Test
        fun `fails when the model does not give it`() {
            model.answers(listOf(RefusalPart("No puedo")))

            assertThatThrownBy { ai.generate<Weather> { user("Que temperatura hay?") } }
                .isInstanceOf(NoObjectGeneratedError::class.java)
        }

        @Test
        fun `or says so without failing, for whoever wants to decide what to do`() {
            model.answers(listOf(RefusalPart("No puedo")))

            val result = ai.generateObject<Weather> { user("Que temperatura hay?") }

            assertThat(result.value).isNull()
            assertThat(result.refusal).isEqualTo("No puedo")
            assertThat(result.error).isInstanceOf(NoObjectGeneratedError::class.java)
            assertThat(result.run.steps).hasSize(1)
        }

        @Test
        fun `and gives it when it came`() {
            model.answers(listOf(TextPart("""{"city":"Bariloche","celsius":7}""")))

            val result = ai.generateObject<Weather> { user("Que temperatura hay en Bariloche?") }

            assertThat(result.value).isEqualTo(Weather("Bariloche", 7))
            assertThat(result.error).isNull()
        }
    }

    @Nested
    inner class stream {
        @Test
        fun `gives the text as it arrives and the result at the end`() {
            model.streams(listOf(StreamPart.TextDelta("Ho"), StreamPart.TextDelta("la")))

            val (text, result) = ai.stream { user("Saludá") }.use { it.textDeltas().joinToString("") to it.result() }

            assertThat(text).isEqualTo("Hola")
            assertThat(result.steps).hasSize(1)
        }

        @Test
        fun `and says which tool is running in between`() {
            model.streams(listOf(StreamPart.PartDone(weatherCall)), listOf(StreamPart.TextDelta("7 grados")))
            model.answers(listOf(weatherCall), listOf(TextPart("7 grados")))

            val events = ai.stream {
                user("Que temperatura hay en Bariloche?")
                tools(weather)
            }.use { it.asSequence().toList() }

            assertThat(events.filterIsInstance<RunEvent.ToolStarted>().single().call.toolName).isEqualTo("getWeather")
            assertThat(weather.cities).containsExactly("Bariloche")
        }
    }

    @Test
    fun `gives the registry to whoever needs a model itself`() {
        assertThat(ai.models()).isSameAs(registry)
    }

    private val model = FakeChatModel(provider = "fake")
    private val provider = FakeProvider(model)
    private val registry = ModelRegistry()
        .addProvider(provider)
        .addAlias("default", "fake/default-model")
        .addAlias("fast", "fake/fast-model")
    private val ai = DefaultAI(registry)
    private val weather = WeatherTool()
    private val weatherCall = ToolCallPart("call_1", "getWeather", Json.obj("city" to "Bariloche"))

    @Serializable
    data class Weather(val city: String, val celsius: Int)

    /** Hands out the same scripted model for every id, and remembers the ids it was asked for. */
    class FakeProvider(private val model: FakeChatModel): AIProvider {
        override val name = "fake"
        val asked = mutableListOf<String>()

        override fun chatModel(modelId: String): ChatModel {
            asked.add(modelId)
            return model
        }
    }

    class WeatherTool: Tool<WeatherTool.Args>(Args.serializer()) {
        override val name = "getWeather"
        override val description = "The current weather of a city"

        val cities = mutableListOf<String>()
        var context: ToolContext? = null
        var failWith: Exception? = null

        override fun execute(args: Args, context: ToolContext): ToolResult {
            failWith?.let { throw it }
            cities.add(args.city)
            this.context = context
            return ToolResult.text("7 grados")
        }

        @Serializable
        data class Args(val city: String)
    }
}
