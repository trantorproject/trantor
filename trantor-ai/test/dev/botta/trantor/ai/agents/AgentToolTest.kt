package dev.botta.trantor.ai.agents

import dev.botta.json.Json
import dev.botta.trantor.ai.DefaultAI
import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.errors.CancelledError
import dev.botta.trantor.ai.generate
import dev.botta.trantor.ai.generation.MaxStepsExceededError
import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.ModelRegistry
import dev.botta.trantor.ai.models.Usage
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.models.cost.CostEstimate
import dev.botta.trantor.ai.providers.AIProvider
import dev.botta.trantor.ai.testing.FakeChatModel
import dev.botta.trantor.ai.tools.*
import dev.botta.trantor.domain.Money
import dev.botta.trantor.primitives.Cancellation
import dev.botta.trantor.serialization.gson.GsonSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** An agent that another one uses as a tool: it runs a run of its own, and the one that called it goes on. */
class AgentToolTest {
    @Test
    fun `the agent it runs gets only the task, and the model reads what it answered`() {
        writerModel.answers(listOf(research("Clima de Bariloche")), listOf(TextPart("Nota lista")))
        researcherModel.answers(listOf(TextPart("Hacen 7 grados")))
        val earlier = listOf(Message.user("Hola"), Message.assistant("Hola!"))

        val result = runner.run(writer(), earlier + Message.user("Escribí una nota de Bariloche"))

        assertThat(researcherModel.requests.single().messages)
            .containsExactly(Message.system("Sos investigador"), Message.user("Clima de Bariloche"))
        assertThat(result.steps[0].step.toolResults.single().output).isEqualTo(ToolOutput.Text("Hacen 7 grados"))
        assertThat(result.text).isEqualTo("Nota lista")
    }

    @Test
    fun `the model is told what the agent is for, and to ask it with everything it needs`() {
        val spec = researcher.asTool(runner, "Averigua el clima de un destino").spec(GsonSerializer())

        assertThat(spec.name).isEqualTo("researcher")
        assertThat(spec.description).isEqualTo("Averigua el clima de un destino")
        assertThat(spec.parameters.path("required")).isEqualTo(Json.array("task"))
        assertThat(spec.parameters.path("properties.task.description")?.asString())
            .contains("sees nothing of this conversation")
    }

    @Test
    fun `an agent that answers an object gives it as JSON`() {
        writerModel.answers(listOf(research("Clima de Bariloche")), listOf(TextPart("Nota lista")))
        researcherModel.answers(listOf(TextPart("""{"city":"Bariloche","celsius":7}""")))
        val answering = Agent("researcher").model(researcherModel).output<CityWeather>().build()

        val result = runner.run(writer(answering), Message.user("Escribí una nota"))

        assertThat(result.steps[0].step.toolResults.single().output)
            .isEqualTo(ToolOutput.Json(Json.obj("city" to "Bariloche", "celsius" to 7)))
    }

    @Test
    fun `its usage and its cost count as the run's, and its run stays in the step where it ran`() {
        writerModel.usage = Usage(inputTokens = 100, outputTokens = 10)
        writerModel.cost = cost("0.001")
        researcherModel.usage = Usage(inputTokens = 50, outputTokens = 5)
        researcherModel.cost = cost("0.010")
        writerModel.answers(listOf(research("Clima de Bariloche")), listOf(TextPart("Nota lista")))
        researcherModel.answers(listOf(TextPart("Hacen 7 grados")))

        val result = runner.run(writer(), Message.user("Escribí una nota"))

        assertThat(result.usage).isEqualTo(Usage(inputTokens = 250, outputTokens = 25))
        assertThat(result.estimatedCost?.input).isEqualTo(Money("0.012"))
        assertThat(result.steps[0].step.toolRuns.getValue("call_1").text).isEqualTo("Hacen 7 grados")
    }

    @Test
    fun `it runs for whom the run that called it runs, with its call options`() {
        val cancellation = Cancellation()
        val options = CallOptions(timeout = 30.seconds, cancellation = cancellation)
        val tenant = Tenant("acme")
        val seen = mutableListOf<Tenant>()
        writerModel.answers(listOf(research("Clima de Bariloche")), listOf(TextPart("Nota lista")))
        researcherModel.answers(listOf(ToolCallPart("call_2", "whoAmI", Json.obj())), listOf(TextPart("acme")))
        val researcher = Agent("researcher").model(researcherModel).tools(WhoAmI(seen)).build()

        runner.run(writer(researcher), Message.user("Escribí una nota")) {
            context(RunContext(tenant))
            callOptions(options)
        }

        assertThat(seen).containsExactly(tenant)
        assertThat(researcherModel.options).isEqualTo(options)
    }

    @Test
    fun `a cancellation of the run that called it stops it, and the run with it`() {
        val cancellation = Cancellation()
        writerModel.answers(listOf(research("Clima de Bariloche")), listOf(TextPart("Nota lista")))
        researcherModel.answers(listOf(ToolCallPart("call_2", "cancel", Json.obj())), listOf(TextPart("Nunca")))
        val researcher = Agent("researcher").model(researcherModel).tools(Cancel(cancellation)).build()

        assertThatThrownBy {
            runner.run(writer(researcher), Message.user("Escribí una nota")) {
                callOptions(CallOptions(cancellation = cancellation))
            }
        }.isInstanceOf(CancelledError::class.java)

        assertThat(researcherModel.requests).hasSize(1)
        assertThat(writerModel.requests).hasSize(1)
    }

    @Test
    fun `agents nested deeper than the limit go back to the model as an error`() {
        val leafModel = FakeChatModel(modelId = "leaf")
        val leaf = Agent("leaf").model(leafModel).build()
        val middle = Agent("middle").model(researcherModel).tools(leaf.asTool(runner, "Hojas", maxDepth = 1)).build()
        writerModel.answers(
            listOf(ToolCallPart("call_1", "middle", Json.obj("task" to "Algo"))),
            listOf(TextPart("Listo")),
        )
        researcherModel.answers(
            listOf(ToolCallPart("call_2", "leaf", Json.obj("task" to "Algo más"))),
            listOf(TextPart("No pude")),
        )
        val root = Agent("writer").model(writerModel).tools(middle.asTool(runner, "Del medio", maxDepth = 1)).build()

        runner.run(root, Message.user("Hacé algo"))

        assertThat(leafModel.requests).isEmpty()
        assertThat((researcherModel.requests[1].messages.last() as Message.Tool).results.single())
            .isEqualTo(
                ToolResultPart(
                    "call_2",
                    "leaf",
                    ToolOutput.Text("leaf runs as a tool at most 1 deep, and here it would run 2 deep"),
                    isError = true,
                ),
            )
    }

    @Test
    fun `an agent that fails goes back to the model as a failed tool, and the exception stays with the application`() {
        writerModel.answers(listOf(research("Clima de Bariloche")), listOf(TextPart("No pude averiguarlo")))
        researcherModel.answers(listOf(ToolCallPart("call_2", "whoAmI", Json.obj())))
        val researcher = Agent("researcher").model(researcherModel).tools(WhoAmI(mutableListOf())).build()
        val writer = Agent("writer").model(writerModel)
            .tools(researcher.asTool(runner, "Investiga") { maxSteps(1) })
            .build()

        val result = runner.run(writer, Message.user("Escribí una nota"))

        assertThat(result.steps[0].step.toolResults.single().output).isEqualTo(ToolOutput.Text("Tool execution failed"))
        assertThat(result.toolFailures.single().error).isInstanceOf(MaxStepsExceededError::class.java)
        assertThat(result.text).isEqualTo("No pude averiguarlo")
    }

    @Test
    fun `a generation can use it too`() {
        val registry = ModelRegistry().addProvider(FakeProvider(writerModel)).addAlias("default", "fake/writer")
        writerModel.answers(listOf(research("Clima de Bariloche")), listOf(TextPart("Nota lista")))
        researcherModel.answers(listOf(TextPart("Hacen 7 grados")))

        val result = DefaultAI(registry).generate {
            user("Escribí una nota")
            tools(researcher.asTool(runner, "Investiga"))
        }

        assertThat(result.steps[0].toolResults.single().output).isEqualTo(ToolOutput.Text("Hacen 7 grados"))
    }

    private fun writer(researcher: Agent = this.researcher) =
        Agent("writer").model(writerModel).tools(researcher.asTool(runner, "Averigua el clima de un destino")).build()

    private fun research(task: String) = ToolCallPart("call_1", "researcher", Json.obj("task" to task))

    private fun cost(input: String) = CostEstimate(Money(input), Money(0), Money(0), Money(0), Money(0))

    private val writerModel = FakeChatModel(modelId = "writer", provider = "fake")
    private val researcherModel = FakeChatModel(modelId = "researcher")
    private val researcher = Agent("researcher").model(researcherModel).instructions("Sos investigador").build()
    private val runner = AgentRunner(ModelRegistry())

    data class CityWeather(val city: String, val celsius: Int)

    data class Tenant(val name: String)

    class NoArgs

    /** Writes down whom the run is for. */
    class WhoAmI(private val seen: MutableList<Tenant>): Tool<NoArgs>() {
        override val name = "whoAmI"
        override val description = "Who the run is for"

        override fun execute(args: NoArgs, context: ToolContext): ToolResult {
            context.run.get<Tenant>()?.let(seen::add)
            return ToolResult.text("acme")
        }
    }

    /** Cancels the run, as a user who closes the page would. */
    class Cancel(private val cancellation: Cancellation): Tool<NoArgs>() {
        override val name = "cancel"
        override val description = "Cancels"

        override fun execute(args: NoArgs, context: ToolContext): ToolResult {
            cancellation.cancel()
            return ToolResult.text("Cancelled")
        }
    }

    class FakeProvider(private val model: FakeChatModel): AIProvider {
        override val name = "fake"

        override fun chatModel(modelId: String) = model
    }
}
