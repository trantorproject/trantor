@file:Suppress("ClassName")

package dev.botta.trantor.ai.schemas

import dev.botta.json.Json
import dev.botta.trantor.ai.schemas.JsonSchemas
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrictSchemaTest {
    @Test
    fun `every property becomes required`() {
        val strict = StrictSchema.of(JsonSchemas.of<Order>())

        assertThat(strict["required"]?.asArray()?.map { it.asString() }).containsExactly("customer", "note", "items")
    }

    @Test
    fun `every object is closed`() {
        val strict = StrictSchema.of(JsonSchemas.of<Order>())

        assertThat(strict["additionalProperties"]?.asBoolean()).isFalse()
        assertThat(strict.path($$"$defs.Item.additionalProperties")?.asBoolean()).isFalse()
    }

    @Test
    fun `definitions take their simple name`() {
        val strict = StrictSchema.of(JsonSchemas.of<Order>())

        assertThat(strict[$$"$defs"]?.asObject()?.keys).containsExactly("Item")
    }

    @Test
    fun `references point to the renamed definitions`() {
        val strict = StrictSchema.of(JsonSchemas.of<Order>())

        assertThat(strict.path("properties.items.items")?.asObject()?.get($$"$ref")?.asString())
            .isEqualTo($$"#/$defs/Item")
    }

    @Test
    fun `a sealed class is any of its subtypes, since no provider takes oneOf in strict mode`() {
        val strict = StrictSchema.of(JsonSchemas.of<Payment>())

        assertThat(strict.toString()).doesNotContain("oneOf")
        assertThat(strict.path($$"$defs.Method.anyOf")?.asArray()?.map { it.asObject()?.get($$"$ref")?.asString() })
            .containsExactly($$"#/$defs/card", $$"#/$defs/cash")
    }

    @Test
    fun `a nullable object is it or null`() {
        val strict = StrictSchema.of(JsonSchemas.of<Payment>())

        assertThat(strict.path("properties.backup.anyOf")?.asArray()).hasSize(2)
    }

    @Test
    fun `the original schema is not touched`() {
        val schema = JsonSchemas.of<Order>()
        val before = schema.toString()

        StrictSchema.of(schema)

        assertThat(schema.toString()).isEqualTo(before)
    }

    @Test
    fun `a schema without objects is left alone`() {
        val schema = Json.obj("type" to "string")

        assertThat(StrictSchema.of(schema)).isEqualTo(Json.obj("type" to "string"))
    }

    @Serializable
    data class Order(val customer: String, val note: String? = null, val items: List<Item> = emptyList())

    @Serializable
    data class Item(val sku: String)

    @Serializable
    data class Payment(val method: Method, val backup: Item? = null)

    @Serializable
    sealed class Method {
        @Serializable
        @SerialName("card")
        data class Card(val last4: String): Method()

        @Serializable
        @SerialName("cash")
        data class Cash(val tendered: Double? = null): Method()
    }
}
