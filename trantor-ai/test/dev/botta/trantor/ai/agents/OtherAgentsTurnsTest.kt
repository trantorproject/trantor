package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.*
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What an agent reads of the turns another agent of its team took in the conversation: what that one said and did,
 * told as context and with its name, and not as answers of its own. Without it, the recordings of step 4 of the plan
 * showed the new agent taking the calls of the one before as its own, to tools it does not have.
 */
class OtherAgentsTurnsTest {
    @Test
    fun `the new agent reads the turns of the one before as what another agent said and did`() {
        supportModel.answers(
            listOf(
                ReasoningPart("Ventas sabe el precio"),
                TextPart("Te paso con ventas"),
                ToolCallPart("call_1", "getWeather", Json.obj("city" to "Bariloche")),
                ToolCallPart("call_2", "transfer_to_sales", Json.obj()),
            ),
        )

        runner.run(support, question) { team(sales) }

        // Its reasoning is its own, and does not go
        assertThat(salesModel.requests.single().messages).containsExactly(
            Message.system("Sos ventas"),
            question,
            told(
                "[support] said: Te paso con ventas",
                """[support] called getWeather with {"city":"Bariloche"}""",
                "[support] called transfer_to_sales with {}",
                "[support] got from getWeather: 7 grados",
                "[support] got from transfer_to_sales: Transferred to sales.",
            ),
        )
    }

    @Test
    fun `turns of other agents in a row are told in one message`() {
        supportModel.answers(
            listOf(ToolCallPart("call_1", "getWeather", Json.obj())),
            listOf(ToolCallPart("call_2", "transfer_to_sales", Json.obj())),
        )

        runner.run(support, question) { team(sales) }

        assertThat(salesModel.requests.single().messages.drop(2)).containsExactly(
            told(
                "[support] called getWeather with {}",
                "[support] got from getWeather: 7 grados",
                "[support] called transfer_to_sales with {}",
                "[support] got from transfer_to_sales: Transferred to sales.",
            ),
        )
    }

    @Test
    fun `a call of the other agent that failed is told as an error`() {
        supportModel.answers(
            listOf(ToolCallPart("call_1", "getPrice", Json.obj()), ToolCallPart("call_2", "transfer_to_sales", Json.obj())),
        )

        runner.run(support, question) { team(sales) }

        assertThat((salesModel.requests.single().messages.last() as Message.User).parts).contains(
            TextPart("[support] got an error from getPrice: There is no tool called getPrice. The tools are: getWeather, transfer_to_sales"),
        )
    }

    @Test
    fun `a refusal and a part only its provider knows are told as they came`() {
        val search = ProviderPart("openai", "web_search_call", Json.obj("status" to "completed"))

        runner.run(sales, question, Message.Assistant(listOf(RefusalPart("No puedo"), search), agent = "support"))

        assertThat(salesModel.requests.single().messages.last()).isEqualTo(
            told("[support] refused: No puedo", """[support] used web_search_call: {"status":"completed"}"""),
        )
    }

    @Test
    fun `its own turns go as they were, even after another agent had the conversation`() {
        supportModel.answers(listOf(ToolCallPart("call_1", "transfer_to_sales", Json.obj())))
        salesModel.answers(listOf(ToolCallPart("call_2", "transfer_to_support", Json.obj())))
        val backAndForth = Agent("sales").model(salesModel).instructions("Sos ventas").handoffs("support").build()

        runner.run(support, question) { team(backAndForth) }

        assertThat(supportModel.requests[1].messages.drop(2)).containsExactly(
            Message.Assistant(listOf(ToolCallPart("call_1", "transfer_to_sales", Json.obj())), agent = "support"),
            Message.Tool(listOf(ToolResultPart("call_1", "transfer_to_sales", ToolOutput.Text("Transferred to sales.")))),
            told(
                "[sales] called transfer_to_support with {}",
                "[sales] got from transfer_to_support: Transferred to support.",
            ),
        )
    }

    @Test
    fun `a run that starts with turns of another agent in its history reads them the same way`() {
        val history = listOf(question, Message.Assistant(listOf(TextPart("Hola")), agent = "support"), next)

        runner.run(sales, history)

        assertThat(salesModel.requests.single().messages)
            .containsExactly(Message.system("Sos ventas"), question, told("[support] said: Hola"), next)
    }

    @Test
    fun `a summary of the conversation goes as it is`() {
        val summary = Message.Summary("Nico viaja a Bariloche en julio")

        assertThat(OtherAgentsTurns.toldTo("sales", listOf(summary, question))).containsExactly(summary, question)
    }

    @Test
    fun `turns that no agent signed go as they are`() {
        // Written by a generation, or kept before the conversation had agents: there is no telling whose they are
        val history = listOf(question, Message.assistant("Hola"), next)

        runner.run(sales, history)

        assertThat(salesModel.requests.single().messages.drop(1)).isEqualTo(history)
    }

    @Test
    fun `what the run keeps are the turns as they were, signed by the agent that took them`() {
        supportModel.answers(listOf(TextPart("Te paso"), ToolCallPart("call_1", "transfer_to_sales", Json.obj())))
        salesModel.answers(listOf(TextPart("Cuesta 100 dólares")))

        val result = runner.run(support, question) { team(sales) }

        assertThat(result.newMessages).containsExactly(
            Message.Assistant(listOf(TextPart("Te paso"), ToolCallPart("call_1", "transfer_to_sales", Json.obj())), agent = "support"),
            Message.Tool(listOf(ToolResultPart("call_1", "transfer_to_sales", ToolOutput.Text("Transferred to sales.")))),
            Message.Assistant(listOf(TextPart("Cuesta 100 dólares")), agent = "sales"),
        )
    }

    private fun told(vararg lines: String) =
        Message.User((listOf(OtherAgentsTurns.PREAMBLE) + lines).map { TextPart(it) })

    private val supportModel = FakeChatModel(modelId = "support-model")
    private val salesModel = FakeChatModel(modelId = "sales-model")
    private val support = Agent("support").model(supportModel).instructions("Sos soporte")
        .tools(WeatherTool()).handoffs("sales").build()
    private val sales = Agent("sales").model(salesModel).instructions("Sos ventas").build()
    private val runner = AgentRunner(ModelRegistry())
    private val question = Message.user("Cuanto sale?")
    private val next = Message.user("Y el clima?")

    class WeatherTool: Tool<WeatherTool.Args>() {
        override val name = "getWeather"
        override val description = "The weather"

        override fun execute(args: Args, context: ToolContext) = ToolResult.text("7 grados")

        @Serializable
        data class Args(val city: String = "")
    }
}
