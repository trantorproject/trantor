@file:Suppress("ClassName")

package dev.botta.trantor.ai.tools

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.domain.Money
import dev.botta.trantor.primitives.lang.Maybe
import dev.botta.trantor.primitives.serialization.Description
import dev.botta.trantor.primitives.serialization.JsonSchemaError
import dev.botta.trantor.serialization.gson.GsonSerializer
import dev.botta.trantor.serialization.gson.adapters.StringValueSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/** A tool, whose args are read and described by the serializer of the run it is part of. */
class ToolTest {
    @Nested
    inner class `what the model is told` {
        @Test
        fun `the name and what the tool is for`() {
            val spec = WeatherTool().spec(serializer)

            assertThat(spec.name).isEqualTo("getWeather")
            assertThat(spec.description).isEqualTo("The current weather of a city")
        }

        @Test
        fun `the args as a schema, out of their type`() {
            val parameters = WeatherTool().spec(serializer).parameters

            assertThat(parameters["type"]?.asString()).isEqualTo("object")
            assertThat(parameters.path("properties.city.type")?.asString()).isEqualTo("string")
            assertThat(parameters.path("properties.city.description")?.asString()).isEqualTo("The city and country")
        }

        @Test
        fun `an arg with a default is not required`() {
            val parameters = WeatherTool().spec(serializer).parameters

            assertThat(parameters["required"]?.asArray()?.map { it.asString() }).containsExactly("city")
        }

        @Test
        fun `the schema is the one the serializer tells, with the types the application registered`() {
            val parameters = OrderTool().spec(storeSerializer).parameters

            assertThat(parameters.path("properties.sku")).isEqualTo(Json.obj("type" to "string", "pattern" to SKU))
            assertThat(parameters.path("properties.price.type")?.asString()).isEqualTo("string")
        }

        @Test
        fun `a type the serializer cannot describe fails, naming where it is`() {
            val serializer = GsonSerializer().apply { registerTypeAdapter(Sku::class.java, SkuAdapter) }

            assertThatThrownBy { OrderTool().spec(serializer) }
                .isInstanceOf(JsonSchemaError::class.java)
                .hasMessageContaining("sku")
        }

        @Test
        fun `is the one of each serializer, for a tool that runs with more than one`() {
            val tool = OrderTool()

            tool.spec(storeSerializer)

            val another = GsonSerializer().apply { registerTypeAdapter(Sku::class.java, SkuAdapter) }

            assertThatThrownBy { tool.spec(another) }.isInstanceOf(JsonSchemaError::class.java)
        }

        @Test
        fun `a tool is strict, holding the model to its schema`() {
            assertThat(RenameTool().spec(serializer).strict).isTrue()
        }

        @Test
        fun `but not one with a Maybe of what can be null, where a strict model could not leave it as it is`() {
            // In strict mode every field is sent, so null would always change it; the log says why it is not strict
            assertThat(ClearTool().spec(serializer).strict).isFalse()
        }

        @Test
        fun `and not when the Maybe is deeper in the args either`() {
            assertThat(BatchClearTool().spec(serializer).strict).isFalse()
        }
    }

    @Nested
    inner class `the type of the args` {
        @Test
        fun `is the one of the tool it extends`() {
            assertThat(WeatherTool().argsType).isEqualTo(typeOf<WeatherTool.Args>())
        }

        @Test
        fun `is given to a tool of a type it does not know`() {
            val tool = EchoTool<WeatherTool.Args>(typeOf<WeatherTool.Args>())

            tool.call(Json.obj("city" to "Bariloche"), context)

            assertThat(tool.received).isEqualTo(WeatherTool.Args("Bariloche"))
        }

        @Test
        fun `and a tool of a type it does not know that was not given it says how to give it`() {
            assertThatThrownBy { UnknownTool<WeatherTool.Args>() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("typeOf")
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
        fun `args of the types of the application are read by the serializer of the run`() {
            val tool = OrderTool()

            tool.call(Json.obj("sku" to "ABC-1", "price" to "10.50"), context.with(storeSerializer))

            assertThat(tool.received).isEqualTo(OrderTool.Args(Sku("ABC-1"), Money("10.50")))
        }

        @Test
        fun `args that are JSON get the input as it came`() {
            val tool = RawTool()
            val input = Json.obj("anything" to Json.array(1, 2))

            tool.call(input, context)

            assertThat(tool.received).isEqualTo(input)
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
        fun `an object, as the serializer of the run writes it`() {
            val result = context.with(storeSerializer).json(Priced(Sku("ABC-1"), Money("10.50")))

            assertThat(result.output).isEqualTo(ToolOutput.Json(Json.obj("sku" to "ABC-1", "price" to "10.50")))
        }
    }

    private fun ToolContext.with(serializer: GsonSerializer) =
        ToolContext(callId, toolName, run, callOptions, serializer)

    private val serializer = GsonSerializer()

    private val storeSerializer = GsonSerializer().apply {
        registerTypeAdapter(
            Sku::class.java,
            StringValueSerializer({ Sku(it) }, { it.value }),
            Json.obj("type" to "string", "pattern" to SKU),
        )
    }

    private val context = ToolContext("call_1", "getWeather", RunContext())

    class WeatherTool: Tool<WeatherTool.Args>() {
        override val name = "getWeather"
        override val description = "The current weather of a city"

        var received: Args? = null
        var context: ToolContext? = null

        override fun execute(args: Args, context: ToolContext): ToolResult {
            received = args
            this.context = context
            return ToolResult.text("7")
        }

        data class Args(
            @Description("The city and country")
            val city: String,
            val units: String = "celsius",
        )
    }

    class SearchTool: Tool<SearchTool.Args>() {
        override val name = "searchProducts"
        override val description = "Searches the catalog"

        var received: Args? = null

        override fun execute(args: Args, context: ToolContext): ToolResult {
            received = args
            return ToolResult.text("nada")
        }

        data class Args(val query: String, val maxPrice: Double?)
    }

    class OrderTool: Tool<OrderTool.Args>() {
        override val name = "placeOrder"
        override val description = "Places an order"

        var received: Args? = null

        override fun execute(args: Args, context: ToolContext): ToolResult {
            received = args
            return ToolResult.text("Placed")
        }

        data class Args(val sku: Sku, val price: Money)
    }

    class RenameTool: Tool<RenameTool.Args>() {
        override val name = "rename"
        override val description = "Renames a contact"

        override fun execute(args: Args, context: ToolContext) = ToolResult.text("Renamed")

        data class Args(val id: String, val name: Maybe<String> = Maybe.None)
    }

    class ClearTool: Tool<ClearTool.Args>() {
        override val name = "clear"
        override val description = "Changes or clears the phone of a contact"

        override fun execute(args: Args, context: ToolContext) = ToolResult.text("Done")

        data class Args(val id: String, val phone: Maybe<String?> = Maybe.None)
    }

    class BatchClearTool: Tool<BatchClearTool.Args>() {
        override val name = "clearMany"
        override val description = "Changes or clears the phones of some contacts"

        override fun execute(args: Args, context: ToolContext) = ToolResult.text("Done")

        data class Args(val changes: List<ClearTool.Args>)
    }

    class RawTool: Tool<JsonObject>() {
        override val name = "raw"
        override val description = "Takes anything"

        var received: JsonObject? = null

        override fun execute(args: JsonObject, context: ToolContext): ToolResult {
            received = args
            return ToolResult.text("Taken")
        }
    }

    class EchoTool<T: Any>(type: KType): Tool<T>(type) {
        override val name = "echo"
        override val description = "Takes its args"

        var received: T? = null

        override fun execute(args: T, context: ToolContext): ToolResult {
            received = args
            return ToolResult.text("Taken")
        }
    }

    class UnknownTool<T: Any>: Tool<T>() {
        override val name = "unknown"
        override val description = "Does not know what it takes"

        override fun execute(args: T, context: ToolContext) = ToolResult.text("Taken")
    }

    data class Sku(val value: String)

    data class Priced(val sku: Sku, val price: Money)

    /** Reads a Sku by an adapter that says nothing of what it reads. */
    object SkuAdapter: TypeAdapter<Sku>() {
        override fun write(writer: JsonWriter, value: Sku) {
            writer.value(value.value)
        }

        override fun read(reader: JsonReader) = Sku(reader.nextString())
    }

    private companion object {
        const val SKU = "^[A-Z]{3}-[0-9]+$"
    }
}
