@file:Suppress("ClassName")

package dev.botta.trantor.ai.schemas

import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.providers.anthropic.AnthropicStrictRules
import dev.botta.trantor.ai.providers.openai.OpenAIStrictRules
import dev.botta.trantor.primitives.serialization.schemaOf
import dev.botta.trantor.serialization.gson.GsonSerializer
import dev.botta.trantor.serialization.gson.adapters.HierarchyTypeAdapterFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The schema a provider holds a model to. The rules of each come from their docs, read on 2026-09-29: OpenAI in
 * "Supported schemas" of Structured Outputs, Anthropic in "JSON Schema limitations", and what Anthropic cannot hold
 * a model to goes to the description as its SDKs do ("How SDK transformation works").
 */
class StrictSchemaTest {
    @Nested
    inner class `for any provider` {
        @Test
        fun `every property becomes required`() {
            forEach { strict ->
                assertThat(strict(serializer.schemaOf<Order>())["required"]?.asArray()?.map { it.asString() })
                    .containsExactly("customer", "note", "items")
            }
        }

        @Test
        fun `every object is closed`() {
            forEach { strict ->
                val schema = strict(serializer.schemaOf<Order>())

                assertThat(schema["additionalProperties"]?.asBoolean()).isFalse()
                assertThat(schema.path($$"$defs.Item.additionalProperties")?.asBoolean()).isFalse()
            }
        }

        @Test
        fun `a oneOf, which a schema written by hand may say, is anyOf, since no provider takes oneOf`() {
            val card = objectOf("last4" to Json.obj("type" to "string"))
            val cash = objectOf("tendered" to Json.obj("type" to "number"))

            forEach { strict ->
                val schema = strict(objectOf("method" to Json.obj("oneOf" to Json.array(card, cash))))

                assertThat(schema.toString()).doesNotContain("oneOf")
                assertThat(schema.path("properties.method.anyOf")?.asArray()).hasSize(2)
            }
        }

        @Test
        fun `a hierarchy is any of its subtypes`() {
            forEach { strict ->
                val subtypes = strict(serializer.schemaOf<Payment>()).path($$"$defs.Method.anyOf")?.asArray()

                assertThat(subtypes?.map { it.asObject()?.get($$"$ref")?.asString() })
                    .containsExactly($$"#/$defs/Card", $$"#/$defs/Cash")
            }
        }

        @Test
        fun `a nullable object is it or null`() {
            forEach { strict ->
                assertThat(strict(serializer.schemaOf<Payment>()).path("properties.backup.anyOf")?.asArray()).hasSize(2)
            }
        }

        @Test
        fun `the original schema is not touched`() {
            forEach { strict ->
                val schema = serializer.schemaOf<Order>()
                val before = schema.toString()

                strict(schema)

                assertThat(schema.toString()).isEqualTo(before)
            }
        }

        @Test
        fun `a schema without objects is left alone`() {
            forEach { strict ->
                assertThat(strict(Json.obj("type" to "string"))).isEqualTo(Json.obj("type" to "string"))
            }
        }

        @Test
        fun `a keyword no provider lists goes to the description, rather than turning the call down`() {
            forEach { strict ->
                val schema = strict(Json.obj("type" to "string", "contentEncoding" to "base64"))

                assertThat(schema).isEqualTo(string("""{contentEncoding: "base64"}"""))
            }
        }

        @Test
        fun `a property named like a keyword is still a property`() {
            forEach { strict ->
                val schema = strict(objectOf("minimum" to Json.obj("type" to "integer")))

                assertThat(schema.path("properties.minimum")).isEqualTo(Json.obj("type" to "integer"))
            }
        }

        private fun forEach(check: ((JsonObject) -> JsonObject) -> Unit) {
            check { StrictSchema.of(it, OpenAIStrictRules) }
            check { StrictSchema.of(it, AnthropicStrictRules) }
        }
    }

    @Nested
    inner class `for OpenAI` {
        @Test
        fun `keeps what it holds a model to, a pattern, its formats and the bounds of numbers and of lists`() {
            val properties = arrayOf(
                "code" to Json.obj("type" to "string", "pattern" to "^[A-Z]{2}$"),
                "day" to Json.obj("type" to "string", "format" to "date"),
                "count" to Json.obj("type" to "integer", "minimum" to 1, "maximum" to 10, "multipleOf" to 1),
                "ratio" to Json.obj("type" to "number", "exclusiveMinimum" to 0, "exclusiveMaximum" to 1),
                "tags" to listOfStrings().with("minItems", 1).with("maxItems", 3),
            )

            val schema = StrictSchema.of(objectOf(*properties), OpenAIStrictRules)

            assertThat(schema["properties"]).isEqualTo(Json.obj(*properties))
        }

        @Test
        fun `says the length of a string in its description, after what it said`() {
            val name = Json.obj("type" to "string", "description" to "The name", "minLength" to 1, "maxLength" to 40)

            val schema = StrictSchema.of(objectOf("name" to name), OpenAIStrictRules)

            assertThat(schema.path("properties.name"))
                .isEqualTo(Json.obj("type" to "string", "description" to "The name\n\n{minLength: 1, maxLength: 40}"))
        }

        @Test
        fun `and a format it does not know`() {
            val site = Json.obj("type" to "string", "format" to "uri")

            val schema = StrictSchema.of(objectOf("site" to site), OpenAIStrictRules)

            assertThat(schema.path("properties.site")).isEqualTo(string("""{format: "uri"}"""))
        }
    }

    @Nested
    inner class `for Anthropic` {
        @Test
        fun `says the bounds of numbers and of strings in the description`() {
            val properties = objectOf(
                "count" to Json.obj("type" to "integer", "minimum" to 1, "maximum" to 10),
                "name" to Json.obj("type" to "string", "minLength" to 1),
            )

            val schema = StrictSchema.of(properties, AnthropicStrictRules)

            assertThat(schema.path("properties.count"))
                .isEqualTo(Json.obj("type" to "integer", "description" to "{minimum: 1, maximum: 10}"))
            assertThat(schema.path("properties.name")).isEqualTo(string("{minLength: 1}"))
        }

        @Test
        fun `keeps a list that asks for one item at least, and says any other bound in the description`() {
            val properties = objectOf(
                "one" to listOfStrings().with("minItems", 1),
                "two" to listOfStrings().with("minItems", 2).with("maxItems", 5),
            )

            val schema = StrictSchema.of(properties, AnthropicStrictRules)

            assertThat(schema.path("properties.one.minItems")?.asInt()).isEqualTo(1)
            assertThat(schema.path("properties.two"))
                .isEqualTo(listOfStrings().with("description", "{minItems: 2, maxItems: 5}"))
        }

        @Test
        fun `keeps a pattern, its formats and the label of a subtype`() {
            val properties = arrayOf(
                "code" to Json.obj("type" to "string", "pattern" to "^[A-Z]{2}$"),
                "site" to Json.obj("type" to "string", "format" to "uri"),
                "type" to Json.obj("type" to "string", "const" to "card"),
            )

            val schema = StrictSchema.of(objectOf(*properties), AnthropicStrictRules)

            assertThat(schema["properties"]).isEqualTo(Json.obj(*properties))
        }
    }

    @Nested
    inner class `a schema that refers to itself` {
        @Test
        fun `is told apart, from the root`() {
            val tree = objectOf("children" to Json.obj("type" to "array", "items" to Json.obj($$"$ref" to "#")))

            assertThat(StrictSchema.refersToItself(tree)).isTrue()
        }

        @Test
        fun `and through its definitions`() {
            val node = objectOf("next" to Json.obj($$"$ref" to $$"#/$defs/Node"))
            val schema = objectOf("head" to Json.obj($$"$ref" to $$"#/$defs/Node"))
                .with($$"$defs", Json.obj("Node" to node))

            assertThat(StrictSchema.refersToItself(schema)).isTrue()
        }

        @Test
        fun `while one that only refers to its definitions is not`() {
            assertThat(StrictSchema.refersToItself(serializer.schemaOf<Payment>())).isFalse()
        }
    }

    private fun objectOf(vararg properties: Pair<String, JsonObject>) =
        Json.obj("type" to "object", "properties" to Json.obj(*properties))

    private fun string(description: String) = Json.obj("type" to "string", "description" to description)

    private fun listOfStrings() = Json.obj("type" to "array", "items" to Json.obj("type" to "string"))

    private val serializer = GsonSerializer().apply {
        registerTypeAdapterFactory(
            HierarchyTypeAdapterFactory.of<Method>().subtype<Method.Card>("card").subtype<Method.Cash>("cash"),
        )
    }

    data class Order(val customer: String, val note: String? = null, val items: List<Item> = emptyList())

    data class Item(val sku: String)

    data class Payment(val method: Method, val backup: Item? = null)

    sealed class Method {
        data class Card(val last4: String): Method()

        data class Cash(val tendered: Double? = null): Method()
    }
}
