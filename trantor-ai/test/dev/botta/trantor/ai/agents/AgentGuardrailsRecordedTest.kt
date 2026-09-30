@file:Suppress("ClassName")

package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.trantor.ai.generation.RunEvent
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.providers.anthropic.AnthropicChatModel
import dev.botta.trantor.ai.providers.anthropic.AnthropicConfig
import dev.botta.trantor.ai.providers.openai.OpenAIChatModel
import dev.botta.trantor.ai.providers.openai.OpenAIConfig
import dev.botta.trantor.ai.testing.FakeHttpClient
import dev.botta.trantor.ai.tools.Tool
import dev.botta.trantor.ai.tools.ToolContext
import dev.botta.trantor.ai.tools.ToolResult
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Guardrails against what the providers really answered. On the input, a guardrail that asks the same model whether
 * the request is something a travel agency takes care of: its call is the first one of each recording. On the
 * output, in a stream, one that stops an answer naming a competitor; both models named it when asked about it.
 */
class AgentGuardrailsRecordedTest {
    @Nested
    inner class `On the input` {
        @Test
        fun `o4-mini lets a question about the weather through, and the agent answers it`() {
            http.answers(*fixtures("openai/guardrail-on-topic", 3, "json"))

            val result = run(openAI(), "Qué clima hay en Bariloche?")

            assertThat(requests()[0]).contains("aboutTravel").doesNotContain("getWeather")
            assertThat(requests()[1]).contains("Sos el soporte de una agencia de viajes.", "getWeather")
            assertThat(result.text).contains("7")
        }

        @Test
        fun `and stops the one for a poem with the reason the model gave, before the agent is called`() {
            http.answers(*fixtures("openai/guardrail-off-topic", 1, "json"))

            val error = catchThrowableOfType(GuardrailTrippedError::class.java) {
                run(openAI(), "Escribime un poema sobre gatos")
            }

            assertThat(error.guardrail).isEqualTo("AboutTravel")
            assertThat(error.reason).isEqualTo("El usuario solicita un poema sobre gatos, sin relación con viajes.")
            assertThat(http.requests).hasSize(1)
        }

        @Test
        fun `Claude Sonnet 4-5 lets a question about the weather through, and the agent answers it`() {
            http.answers(*fixtures("anthropic/guardrail-on-topic", 3, "json"))

            val result = run(anthropic(), "Qué clima hay en Bariloche?")

            assertThat(requests()[0]).contains("aboutTravel").doesNotContain("getWeather")
            assertThat(requests()[1]).contains("Sos el soporte de una agencia de viajes.", "getWeather")
            assertThat(result.text).contains("7°C")
        }

        @Test
        fun `and stops the one for a poem`() {
            http.answers(*fixtures("anthropic/guardrail-off-topic", 1, "json"))

            val error = catchThrowableOfType(GuardrailTrippedError::class.java) {
                run(anthropic(), "Escribime un poema sobre gatos")
            }

            assertThat(error.reason).startsWith("El usuario solicita escribir un poema sobre gatos")
            assertThat(http.requests).hasSize(1)
        }

        private fun run(model: ChatModel, question: String): AgentRunResult {
            val support = Agent("support")
                .model(model)
                .instructions("Sos el soporte de una agencia de viajes.")
                .tools(WeatherTool())
                .inputGuardrails(AboutTravel(model))
                .build()

            return runner.run(support, Message.user(question))
        }
    }

    @Nested
    inner class `On the output, in a stream` {
        @Test
        fun `o4-mini's answer comes out once the guardrail passed`() {
            http.answers(*fixtures("openai/guardrail-output-pass", 2, "txt"))

            val events = streamAnswering(openAI(), "Qué clima hay en Bariloche?")

            assertThat(textOf(events)).startsWith("En Bariloche la temperatura actual es de 7")
            assertThat(checkedBeforeTheText).containsOnly(true)
        }

        @Test
        fun `and nothing of the one that names a competitor comes out`() {
            http.answers(*fixtures("openai/guardrail-output-trip", 1, "txt"))

            val (events, error) = streamTripping(openAI())

            assertThat(textOf(events)).isEmpty()
            assertThat(events.last()).isEqualTo(tripped)
            assertThat(error.result!!.text).containsIgnoringCase("despegar")
        }

        @Test
        fun `Claude Sonnet 4-5's answer comes out once the guardrail passed`() {
            http.answers(*fixtures("anthropic/guardrail-output-pass", 2, "txt"))

            val events = streamAnswering(anthropic(), "Qué clima hay en Bariloche?")

            assertThat(textOf(events)).startsWith("El clima actual en Bariloche, Argentina es de **7°C**.")
            assertThat(checkedBeforeTheText).containsOnly(true)
        }

        @Test
        fun `and nothing of its answer naming a competitor comes out either`() {
            http.answers(*fixtures("anthropic/guardrail-output-trip", 1, "txt"))

            val (events, error) = streamTripping(anthropic())

            assertThat(textOf(events)).isEmpty()
            assertThat(events.last()).isEqualTo(tripped)
            assertThat(error.result!!.text).containsIgnoringCase("despegar")
        }

        private val tripped = RunEvent.GuardrailTripped("NoCompetitors", "The answer names a competitor")

        /** Whether the guardrail had checked the answer by the time each piece of its text came out. */
        private val checkedBeforeTheText = mutableListOf<Boolean>()

        private fun streamAnswering(model: ChatModel, question: String): List<RunEvent> {
            val guardrail = NoCompetitors()

            return runner.stream(support(model, guardrail), Message.user(question)).use { stream ->
                stream.asSequence()
                    .onEach { if (isText(it)) checkedBeforeTheText.add(guardrail.checked) }
                    .toList()
            }
        }

        private fun streamTripping(model: ChatModel): Pair<List<RunEvent>, GuardrailTrippedError> {
            val question = Message.user("Me conviene comprar el paquete a Bariloche en Despegar o con ustedes?")
            val events = mutableListOf<RunEvent>()

            runner.stream(support(model, NoCompetitors()), question).use { stream ->
                val error = catchThrowableOfType(GuardrailTrippedError::class.java) {
                    stream.forEach { events.add(it) }
                }

                return events to error
            }
        }

        private fun support(model: ChatModel, guardrail: OutputGuardrail) = Agent("support")
            .model(model)
            .instructions("Sos el soporte de una agencia de viajes.")
            .tools(WeatherTool())
            .outputGuardrails(guardrail)
            .build()

        private fun isText(event: RunEvent) = event is RunEvent.Model && event.part is StreamPart.TextDelta

        private fun textOf(events: List<RunEvent>) = events.filterIsInstance<RunEvent.Model>()
            .mapNotNull { (it.part as? StreamPart.TextDelta)?.text }
            .joinToString("")
    }

    private fun openAI() = OpenAIChatModel("o4-mini", OpenAIConfig("sk-test"), http)

    private fun anthropic() = AnthropicChatModel("claude-sonnet-4-5", AnthropicConfig(apiKey = "sk-ant-test"), http)

    private fun requests() = http.requests.map { it.body as String }

    private fun fixtures(name: String, count: Int, extension: String) =
        (1..count).map { fixture("$name-$it.$extension") }.toTypedArray()

    private fun fixture(name: String) = javaClass.getResource("/$name")?.readText() ?: error("Missing fixture $name")

    private val http = FakeHttpClient()
    private val runner = AgentRunner(ModelRegistry())

    /** The guardrails and the tool the recordings were made with. */
    class AboutTravel(private val model: ChatModel): InputGuardrail {
        override fun check(run: AgentHookContext, conversation: List<Message>): GuardrailVerdict {
            val request = ChatRequest(
                listOf(
                    Message.system(
                        "Decidí si el mensaje del usuario es algo que atiende una agencia de viajes: viajes, destinos, " +
                            "clima, precios o reservas. En reason, explicá en una oración por qué.",
                    ),
                    conversation.last(),
                ),
                output = OutputSpec.json<Topic>(),
            )
            val topic = model.generate(request).objectAs<Topic>()

            return if (topic.aboutTravel) GuardrailVerdict.Pass else GuardrailVerdict.Trip(topic.reason)
        }

        @Serializable
        data class Topic(val aboutTravel: Boolean, val reason: String)
    }

    class NoCompetitors: OutputGuardrail {
        var checked = false

        override fun check(run: AgentHookContext, result: AgentRunResult): GuardrailVerdict {
            checked = true

            return if ("despegar" in result.text.lowercase()) GuardrailVerdict.Trip("The answer names a competitor")
            else GuardrailVerdict.Pass
        }
    }

    class WeatherTool: Tool<WeatherTool.Args>() {
        override val name = "getWeather"
        override val description = "The current weather of a city, in celsius"

        override fun execute(args: Args, context: ToolContext) = ToolResult.json(Json.obj("celsius" to 7))

        @Serializable
        data class Args(val city: String)
    }
}
