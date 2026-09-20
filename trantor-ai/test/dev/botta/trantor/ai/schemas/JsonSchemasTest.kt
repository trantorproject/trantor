@file:Suppress("ClassName")

package dev.botta.trantor.ai.schemas

import dev.botta.json.Json
import kotlinx.schema.generator.json.SerialDescription
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.reflect.typeOf

class JsonSchemasTest {
    @Test
    fun `types of the properties`() {
        val schema = JsonSchemas.of<Item>()

        assertThat(schema["type"]?.asString()).isEqualTo("object")
        assertThat(schema.path("properties.sku.type")?.asString()).isEqualTo("string")
        assertThat(schema.path("properties.quantity.type")?.asString()).isEqualTo("integer")
    }

    @Test
    fun `descriptions of the properties`() {
        val schema = JsonSchemas.of<Order>()

        assertThat(schema.path("properties.customer.description")?.asString()).isEqualTo("Who ordered")
    }

    @Test
    fun `a property with a default is not required`() {
        val schema = JsonSchemas.of<Order>()

        assertThat(schema["required"]?.asArray()?.map { it.asString() })
            .containsExactly("customer", "items", "status")
    }

    @Test
    fun `nested types go to the definitions`() {
        val schema = JsonSchemas.of<Order>()

        assertThat(schema.path("properties.items.items")?.asObject()?.get($$"$ref")?.asString())
            .isEqualTo($$"#/$defs/dev.botta.trantor.ai.schemas.JsonSchemasTest.Item")
        assertThat(schema[$$"$defs"]?.asObject()?.keys).contains("dev.botta.trantor.ai.schemas.JsonSchemasTest.Item")
    }

    @Test
    fun `an enum becomes its values`() {
        val schema = JsonSchemas.of<Order>()

        val status = schema[$$"$defs"]?.asObject()?.get("dev.botta.trantor.ai.schemas.JsonSchemasTest.Status")
        assertThat(status?.asObject()?.get("enum")?.asArray()?.map { it.asString() })
            .containsExactly("Pending", "Shipped")
    }

    @Test
    fun `a nullable property takes both types`() {
        val schema = JsonSchemas.of<Order>()

        assertThat(schema.path("properties.note.type")?.asArray()?.map { it.asString() })
            .containsExactly("string", "null")
    }

    @Test
    fun `without the metadata of the schema itself`() {
        val schema = JsonSchemas.of<Item>()

        assertThat(schema.containsKey($$"$schema")).isFalse()
        assertThat(schema.containsKey($$"$id")).isFalse()
    }

    @Test
    fun `from a type given at runtime`() {
        val schema = JsonSchemas.of(typeOf<Item>())

        assertThat(schema).isEqualTo(Json.parse(JsonSchemas.of<Item>().toString()))
    }

    @Serializable
    data class Order(
        @SerialDescription("Who ordered")
        val customer: String,
        val items: List<Item>,
        val status: Status,
        val note: String? = null,
    )

    @Serializable
    data class Item(val sku: String, val quantity: Int)

    @Serializable
    enum class Status { Pending, Shipped }
}
