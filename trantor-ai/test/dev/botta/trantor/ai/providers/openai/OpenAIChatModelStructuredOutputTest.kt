@file:Suppress("ClassName")

package dev.botta.trantor.ai.providers.openai

import dev.botta.json.Json
import dev.botta.trantor.ai.errors.NoObjectGeneratedError
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeHttpClient
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import dev.botta.trantor.serialization.gson.GsonSerializer

class OpenAIChatModelStructuredOutputTest {
    @Test
    fun `asks for the answer as a schema`() {
        httpClient.body = fixture("structured-output")

        model.generate(orderRequest())

        val format = sentBody().path("text.format")?.asObject()!!
        assertThat(format["type"]?.asString()).isEqualTo("json_schema")
        assertThat(format["name"]?.asString()).isEqualTo("order")
        assertThat(format["strict"]?.asBoolean()).isTrue()
        assertThat(format.path("schema.properties.customer.type")?.asString()).isEqualTo("string")
    }

    @Test
    fun `the schema it sends follows the rules of strict mode`() {
        httpClient.body = fixture("structured-output")

        model.generate(orderRequest())

        val schema = sentBody().path("text.format.schema")?.asObject()!!
        assertThat(schema["additionalProperties"]?.asBoolean()).isFalse()
        assertThat(schema["required"]?.asArray()?.map { it.asString() })
            .containsExactly("customer", "items", "deliverBefore")
        assertThat(schema.path("properties.items.items")?.asObject()?.get($$"$ref")?.asString())
            .isEqualTo($$"#/$defs/Item")
    }

    @Test
    fun `without strict the schema goes as it is`() {
        httpClient.body = fixture("structured-output")
        val output = OutputSpec.json<Order>(GsonSerializer(), name = "order", strict = false)

        model.generate(ChatRequest(listOf(Message.user("Leé el pedido")), output = output))

        assertThat(sentBody().path("text.format.strict")?.asBoolean()).isFalse()
        // kotlinx leaves a property with a default out of required; strict mode is what puts it in
        assertThat(sentBody().path("text.format.schema.required")?.asArray()?.map { it.asString() })
            .containsExactly("customer", "items")
    }

    @Test
    fun `a request without structured output does not ask for a format`() {
        httpClient.body = fixture("structured-output")

        model.generate(ChatRequest("Hola"))

        assertThat(sentBody().containsKey("text")).isFalse()
    }

    @Test
    fun `reads the answer as the object`() {
        httpClient.body = fixture("structured-output")

        val order = model.generate(orderRequest()).objectAs<Order>(GsonSerializer())

        assertThat(order).isEqualTo(
            Order("Nico Bottarini", listOf(Item("ABC-100", 3), Item("XYZ-7", 2)), deliverBefore = "2024-04-25")
        )
    }

    @Test
    fun `a refusal is not an object`() {
        httpClient.body = fixture("refusal")

        val response = model.generate(orderRequest())

        assertThatThrownBy { response.objectAs<Order>(GsonSerializer()) }
            .isInstanceOfSatisfying(NoObjectGeneratedError::class.java) {
                assertThat(it.refusal).isEqualTo("I'm sorry, I can't help with that.")
                assertThat(it.finishReason).isEqualTo(FinishReasons.Refusal)
            }
    }

    @Test
    fun `an answer that is not the object asked for fails with what came`() {
        httpClient.body = fixture("text-simple")

        val response = model.generate(orderRequest())

        assertThatThrownBy { response.objectAs<Order>(GsonSerializer()) }
            .isInstanceOfSatisfying(NoObjectGeneratedError::class.java) {
                assertThat(it.text).isEqualTo("Hola, ¿en qué puedo ayudarte?")
            }
    }

    private fun orderRequest() = ChatRequest(
        messages = listOf(Message.user("Leé el pedido")),
        output = OutputSpec.json<Order>(GsonSerializer(), name = "order"),
    )

    private fun sentBody() = Json.parse(httpClient.requestBody!!).asObject()!!

    private fun fixture(name: String) =
        javaClass.getResource("/openai/$name.json")?.readText() ?: error("Missing fixture $name")

    private val httpClient = FakeHttpClient()
    private val model = OpenAIChatModel("gpt-4.1-mini", OpenAIConfig(apiKey = "sk-test"), httpClient)

    @Serializable
    data class Order(val customer: String, val items: List<Item>, val deliverBefore: String? = null)

    @Serializable
    data class Item(val sku: String, val quantity: Int)
}
