@file:Suppress("ClassName")

package dev.botta.trantor.ai.tools

import dev.botta.json.Json
import dev.botta.trantor.ai.RunContext
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolTest {
    @Nested
    inner class `what the model is told` {
        @Test
        fun `the name and what the tool is for`() {
            val spec = WeatherTool().spec()

            assertThat(spec.name).isEqualTo("getWeather")
            assertThat(spec.description).isEqualTo("The current weather of a city")
        }

        @Test
        fun `the args as a schema, out of their type`() {
            val parameters = WeatherTool().spec().parameters

            assertThat(parameters["type"]?.asString()).isEqualTo("object")
            assertThat(parameters.path("properties.city.type")?.asString()).isEqualTo("string")
            assertThat(parameters.path("properties.city.description")?.asString()).isEqualTo("The city and country")
        }

        @Test
        fun `an arg with a default is not required`() {
            val parameters = WeatherTool().spec().parameters

            assertThat(parameters["required"]?.asArray()?.map { it.asString() }).containsExactly("city")
        }
    }

    @Nested
    inner class `what the model sent` {
        @Test
        fun `runs the tool with the args as their type`() {
            val tool = WeatherTool()

            tool.call(Json.obj("city" to "Bariloche", "units" to "fahrenheit"), context)

            assertThat(tool.received).isEqualTo(WeatherTool.Args("Bariloche", "fahrenheit"))
        }

        @Test
        fun `an arg the model left out takes its default`() {
            val tool = WeatherTool()

            tool.call(Json.obj("city" to "Bariloche"), context)

            assertThat(tool.received?.units).isEqualTo("celsius")
        }

        @Test
        fun `a nullable arg with no default the model left out is null`() {
            // Strict mode makes OpenAI send every field, but Anthropic leaves out the ones it has nothing for
            val tool = SearchTool()

            tool.call(Json.obj("query" to "taladro"), context)

            assertThat(tool.received).isEqualTo(SearchTool.Args("taladro", null))
        }

        @Test
        fun `a field the args do not have is ignored`() {
            val tool = WeatherTool()

            tool.call(Json.obj("city" to "Bariloche", "country" to "AR"), context)

            assertThat(tool.received?.city).isEqualTo("Bariloche")
        }

        @Test
        fun `a missing arg fails naming it, so the model can fix the call`() {
            assertThatThrownBy { WeatherTool().call(Json.obj("units" to "celsius"), context) }
                .isInstanceOf(InvalidToolInputError::class.java)
                .hasMessageContaining("city")
                .extracting { (it as InvalidToolInputError).toolName }
                .isEqualTo("getWeather")
        }

        @Test
        fun `an arg of the wrong type fails too`() {
            assertThatThrownBy { SearchTool().call(Json.obj("query" to "taladro", "maxPrice" to "barato"), context) }
                .isInstanceOf(InvalidToolInputError::class.java)
                .hasMessageContaining("maxPrice")
        }

        @Test
        fun `the tool gets the context of the call`() {
            val tool = WeatherTool()

            tool.call(Json.obj("city" to "Bariloche"), context)

            assertThat(tool.context?.callId).isEqualTo("call_1")
        }
    }

    @Nested
    inner class `what the tool answers` {
        @Test
        fun `text`() {
            assertThat(ToolResult.text("7 grados").output).isEqualTo(ToolOutput.Text("7 grados"))
        }

        @Test
        fun `json`() {
            assertThat(ToolResult.json(Json.obj("celsius" to 7)).output)
                .isEqualTo(ToolOutput.Json(Json.obj("celsius" to 7)))
        }

        @Test
        fun `an object, as its json`() {
            val result = ToolResult.json(Weather(celsius = 7, sky = "nublado"))

            assertThat(result.output).isEqualTo(ToolOutput.Json(Json.obj("celsius" to 7, "sky" to "nublado")))
        }
    }

    private val context = ToolContext("call_1", "getWeather", RunContext())

    class WeatherTool: Tool<WeatherTool.Args>(Args.serializer()) {
        override val name = "getWeather"
        override val description = "The current weather of a city"

        var received: Args? = null
        var context: ToolContext? = null

        override fun execute(args: Args, context: ToolContext): ToolResult {
            received = args
            this.context = context
            return ToolResult.text("7")
        }

        @Serializable
        data class Args(
            @SerialDescription("The city and country")
            val city: String,
            val units: String = "celsius",
        )
    }

    class SearchTool: Tool<SearchTool.Args>(Args.serializer()) {
        override val name = "searchProducts"
        override val description = "Searches the catalog"

        var received: Args? = null

        override fun execute(args: Args, context: ToolContext): ToolResult {
            received = args
            return ToolResult.text("nada")
        }

        @Serializable
        data class Args(val query: String, val maxPrice: Double?)
    }

    @Serializable
    data class Weather(val celsius: Int, val sky: String)
}
