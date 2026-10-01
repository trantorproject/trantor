package dev.botta.trantor.ai.history

import dev.botta.trantor.ai.models.chat.ChatModel
import dev.botta.trantor.ai.models.chat.ChatRequest
import dev.botta.trantor.ai.models.chat.Message
import dev.botta.trantor.ai.providers.anthropic.AnthropicChatModel
import dev.botta.trantor.ai.providers.anthropic.AnthropicConfig
import dev.botta.trantor.ai.providers.openai.OpenAIChatModel
import dev.botta.trantor.ai.providers.openai.OpenAIConfig
import dev.botta.trantor.ai.testing.FakeHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A conversation that starts with a summary of what came before, and a question only the summary answers, against
 * what the providers really answered: both took the summary told by the user, and answered with what it said.
 */
class SummaryRecordedTest {
    @Test
    fun `o4-mini answers with what the summary says`() {
        http.body = fixture("openai/sessions/summary-1")

        val response = ask(OpenAIChatModel("o4-mini", OpenAIConfig("sk-test"), http))

        assertThat(http.requestBody).contains(Message.Summary.PREAMBLE, "del 12 al 19 de julio con dos amigos")
        assertThat(response.warnings).isEmpty()
        assertThat(response.text).contains("12", "19", "3")
    }

    @Test
    fun `and so does Claude Sonnet 4-5`() {
        http.body = fixture("anthropic/sessions/summary-1")

        val response = ask(AnthropicChatModel("claude-sonnet-4-5", AnthropicConfig(apiKey = "sk-ant-test"), http))

        assertThat(http.requestBody).contains(Message.Summary.PREAMBLE, "del 12 al 19 de julio con dos amigos")
        assertThat(response.warnings).isEmpty()
        assertThat(response.text).contains("12", "19", "3 personas")
    }

    private fun ask(model: ChatModel) = model.generate(
        ChatRequest(
            Message.system("Sos el asistente de una agencia de viajes. Respondé corto."),
            Message.Summary(
                "Nico planea un viaje a Bariloche del 12 al 19 de julio con dos amigos. Quiere esquiar en el Cerro " +
                    "Catedral y ya reservó una cabaña cerca de la base del cerro.",
            ),
            Message.user("¿Qué fechas era el viaje y cuántos somos en total?"),
        ),
    )

    private fun fixture(name: String) =
        javaClass.getResource("/$name.json")?.readText() ?: error("Missing fixture $name")

    private val http = FakeHttpClient()
}
