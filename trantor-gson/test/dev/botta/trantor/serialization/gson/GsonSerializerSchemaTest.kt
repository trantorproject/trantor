@file:Suppress("ClassName")

package dev.botta.trantor.serialization.gson

import com.google.gson.JsonDeserializer
import com.google.gson.annotations.SerializedName
import dev.botta.json.Json
import dev.botta.trantor.domain.Email
import dev.botta.trantor.domain.Id
import dev.botta.trantor.domain.Money
import dev.botta.trantor.primitives.lang.Maybe
import dev.botta.trantor.primitives.serialization.Description
import dev.botta.trantor.primitives.serialization.JsonSchemaError
import dev.botta.trantor.primitives.serialization.schemaOf
import dev.botta.trantor.primitives.validation.NullOrNotBlank
import dev.botta.trantor.serialization.gson.adapters.HierarchyTypeAdapterFactory
import dev.botta.trantor.serialization.gson.adapters.StringValueSerializer
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.util.UUID

/**
 * The JSON Schema of what the serializer reads: the same rules as reading, told as a schema. Each test says what is
 * promised for one kind of type.
 */
class GsonSerializerSchemaTest {
    @Nested
    inner class `a class` {
        @Test
        fun `is an object with a property for each parameter of its primary constructor`() {
            assertThat(serializer.schemaOf<Plain>()).isEqualTo(
                json(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "name": {"type": "string"},
                        "count": {"type": "integer"},
                        "big": {"type": "integer"},
                        "ratio": {"type": "number"},
                        "price": {"type": "number"},
                        "on": {"type": "boolean"},
                        "letter": {"type": "string", "minLength": 1, "maxLength": 1}
                      },
                      "required": ["name", "count", "big", "ratio", "price", "on", "letter"],
                      "additionalProperties": false
                    }
                    """,
                ),
            )
        }

        @Test
        fun `requires what cannot be null and has no default, and lets a nullable one be null`() {
            assertThat(serializer.schemaOf<Options>()).isEqualTo(
                json(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "name": {"type": "string"},
                        "count": {"type": "integer"},
                        "label": {"type": ["string", "null"]},
                        "note": {"type": ["string", "null"]}
                      },
                      "required": ["name"],
                      "additionalProperties": false
                    }
                    """,
                ),
            )
        }

        @Test
        fun `names a property by its serialized name`() {
            assertThat(serializer.schemaOf<Named>().path("properties")?.asObject()?.keys).containsExactly("full_name")
        }

        @Test
        fun `leaves out the properties of the body, which are not how it is built`() {
            assertThat(serializer.schemaOf<WithBody>().path("properties")?.asObject()?.keys).containsExactly("name")
        }

        @Test
        fun `goes to the definitions when another uses it, and a nullable one is either it or null`() {
            assertThat(serializer.schemaOf<Order>()).isEqualTo(
                json(
                    $$"""
                    {
                      "type": "object",
                      "properties": {
                        "lines": {"type": "array", "items": {"$ref": "#/$defs/Line"}},
                        "backup": {"anyOf": [{"$ref": "#/$defs/Line"}, {"type": "null"}]},
                        "stock": {"type": "object", "additionalProperties": {"type": "integer"}},
                        "tags": {"type": "array", "items": {"type": "string"}},
                        "color": {"$ref": "#/$defs/Color"}
                      },
                      "required": ["lines", "stock", "tags", "color"],
                      "additionalProperties": false,
                      "$defs": {
                        "Line": {
                          "type": "object",
                          "properties": {"sku": {"type": "string"}, "quantity": {"type": "integer"}},
                          "required": ["sku"],
                          "additionalProperties": false
                        },
                        "Color": {"type": "string", "enum": ["RED", "blue"]}
                      }
                    }
                    """,
                ),
            )
        }

        @Test
        fun `takes a generic class with the type it is declared with`() {
            val defs = serializer.schemaOf<Catalog>()[DEFS]?.asObject()!!

            assertThat(defs.keys).containsExactly("PageOfLine", "Line")
            assertThat(defs.path("PageOfLine.properties.items.items")).isEqualTo(json($$"""{"$ref": "#/$defs/Line"}"""))
        }

        @Test
        fun `that contains itself refers to itself`() {
            assertThat(serializer.schemaOf<Node>().path("properties.children.items"))
                .isEqualTo(json($$"""{"$ref": "#"}"""))
            assertThat(serializer.schemaOf<Tree>().path("$DEFS.Node.properties.children.items"))
                .isEqualTo(json($$"""{"$ref": "#/$defs/Node"}"""))
        }

        @Test
        fun `keeps its package in the definitions when another class has the same simple name`() {
            val defs = serializer.schemaOf<TwoItems>()[DEFS]?.asObject()!!

            assertThat(defs.keys).containsExactly("Item", Other.Item::class.qualifiedName)
        }

        @Test
        fun `says what it is and what its properties are with their description`() {
            val schema = serializer.schemaOf<Described>()

            assertThat(schema["description"]?.asString()).isEqualTo("A thing to describe")
            assertThat(schema.path("properties.name.description")?.asString()).isEqualTo("What people call it")
            assertThat(schema.path("properties.line")).isEqualTo(
                json($$"""{"$ref": "#/$defs/Line", "description": "The one it came from"}"""),
            )
        }
    }

    @Nested
    inner class `the types of the domain` {
        @Test
        fun `are strings, with the format or the pattern the serializer reads`() {
            assertThat(serializer.schemaOf<Domain>().path("properties")).isEqualTo(
                json(
                    """
                    {
                      "id": {"type": "string", "format": "uuid"},
                      "uuid": {"type": "string", "format": "uuid"},
                      "email": {"type": "string", "format": "email"},
                      "day": {"type": "string", "format": "date"},
                      "at": {"type": "string", "format": "date-time"},
                      "time": {"type": "string", "pattern": "^\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?$"},
                      "month": {"type": "string", "pattern": "^\\d{4}-\\d{2}$"},
                      "amount": {"type": "string", "pattern": "^-?\\d+(\\.\\d+)?$"}
                    }
                    """,
                ),
            )
        }

        @Test
        fun `a Maybe is what it holds, and is never required`() {
            val schema = serializer.schemaOf<Patch>()

            assertThat(schema.path("properties.phone")).isEqualTo(json("""{"type": ["string", "null"]}"""))
            assertThat(schema["required"]).isEqualTo(Json.array("id"))
        }

        @Test
        fun `a Maybe of what cannot be null takes null too, which leaves it as it is`() {
            val schema = serializer.schemaOf<Rename>()

            assertThat(schema.path("properties.name")).isEqualTo(json("""{"type": ["string", "null"]}"""))
            assertThat(schema["required"]).isEqualTo(Json.array("id"))
        }

        @Test
        fun `a value class is its value`() {
            assertThat(serializer.schemaOf<Country>().path("properties")).isEqualTo(
                json("""{"code": {"type": "string"}, "region": {"type": ["string", "null"]}}"""),
            )
        }
    }

    @Nested
    inner class `the validations` {
        @Test
        fun `of Jakarta say what the validation will ask for`() {
            assertThat(serializer.schemaOf<Validated>().path("properties")).isEqualTo(
                json(
                    """
                    {
                      "name": {"type": "string", "minLength": 1},
                      "code": {"type": "string", "minLength": 2, "maxLength": 10},
                      "nick": {"type": ["string", "null"], "minLength": 1},
                      "tags": {"type": "array", "items": {"type": "string"}, "minItems": 1},
                      "picks": {"type": "array", "items": {"type": "string"}, "maxItems": 3},
                      "count": {"type": "integer", "minimum": 1, "maximum": 5},
                      "positive": {"type": "integer", "exclusiveMinimum": 0},
                      "zeroOrMore": {"type": "integer", "minimum": 0},
                      "rate": {"type": "number", "minimum": 0.5},
                      "above": {"type": "number", "exclusiveMinimum": 0},
                      "zip": {"type": "string", "pattern": "^[0-9]{4}$"},
                      "contact": {"type": "string", "format": "email"}
                    }
                    """,
                ),
            )
        }

        @Test
        fun `are read also when they are on the parameter and not on the field`() {
            assertThat(serializer.schemaOf<OnParameter>().path("properties.name.minLength")?.asInt()).isEqualTo(1)
        }
    }

    @Nested
    inner class `a hierarchy` {
        @Test
        fun `is any of its subtypes, each with its label as a constant it requires`() {
            val serializer = GsonSerializer().apply {
                registerTypeAdapterFactory(
                    HierarchyTypeAdapterFactory.of<Config>()
                        .subtype<Config.Http>("http")
                        .subtype<Config.Script>("script"),
                )
            }

            assertThat(serializer.schemaOf<Tool>()).isEqualTo(
                json(
                    $$"""
                    {
                      "type": "object",
                      "properties": {"config": {"$ref": "#/$defs/Config"}},
                      "required": ["config"],
                      "additionalProperties": false,
                      "$defs": {
                        "Config": {"anyOf": [{"$ref": "#/$defs/Http"}, {"$ref": "#/$defs/Script"}]},
                        "Http": {
                          "type": "object",
                          "properties": {"type": {"type": "string", "const": "http"}, "url": {"type": "string"}},
                          "required": ["type", "url"],
                          "additionalProperties": false
                        },
                        "Script": {
                          "type": "object",
                          "properties": {"type": {"type": "string", "const": "script"}, "code": {"type": "string"}},
                          "required": ["type", "code"],
                          "additionalProperties": false
                        }
                      }
                    }
                    """,
                ),
            )
        }

        @Test
        fun `uses the name of its label`() {
            val serializer = GsonSerializer().apply {
                registerTypeAdapterFactory(HierarchyTypeAdapterFactory.of<Config>("kind").subtype<Config.Http>("http"))
            }

            assertThat(serializer.schemaOf<Tool>().path("$DEFS.Http.properties.kind.const")?.asString())
                .isEqualTo("http")
        }
    }

    @Nested
    inner class `a type with an adapter of the application` {
        @Test
        fun `read by StringValueSerializer is a string, or the schema it was given`() {
            val codes = Json.obj("type" to "string", "pattern" to "^[A-Z]+$")
            val serializer = GsonSerializer().apply {
                registerTypeAdapter(Key::class.java, StringValueSerializer({ Key(it) }, { it.value }))
                registerTypeAdapter(Code::class.java, StringValueSerializer({ Code(it) }, { it.value }, codes))
            }

            assertThat(serializer.schemaOf<Locked>().path("properties")).isEqualTo(
                json("""{"key": {"type": "string"}, "code": {"type": "string", "pattern": "^[A-Z]+$"}}"""),
            )
        }

        @Test
        fun `takes the schema registered with it, or on its own`() {
            val serializer = GsonSerializer().apply {
                registerTypeAdapter(Key::class.java, keyAdapter, Json.obj("type" to "string", "format" to "hostname"))
                registerTypeAdapter(Code::class.java, keyAdapter)
                registerSchema(Code::class.java, Json.obj("type" to "string", "maxLength" to 2))
            }

            assertThat(serializer.schemaOf<Locked>().path("properties")).isEqualTo(
                json(
                    """{"key": {"type": "string", "format": "hostname"}, "code": {"type": "string", "maxLength": 2}}""",
                ),
            )
        }

        @Test
        fun `without a schema fails, saying where it is and how to give one`() {
            val serializer = GsonSerializer().apply { registerTypeAdapter(Key::class.java, keyAdapter) }

            assertThatThrownBy { serializer.schemaOf<Holder>() }
                .isInstanceOf(JsonSchemaError::class.java)
                .hasMessageContaining("Holder.locked.key")
                .hasMessageContaining(Key::class.qualifiedName)
                .hasMessageContaining("registerSchema")
        }
    }

    private fun json(text: String) = Json.parse(text.trimIndent()).asObject()!!

    private val serializer = GsonSerializer()

    private val keyAdapter = JsonDeserializer { json, _, _ -> Key(json.asString) }

    data class Plain(
        val name: String,
        val count: Int,
        val big: Long,
        val ratio: Double,
        val price: BigDecimal,
        val on: Boolean,
        val letter: Char,
    )

    data class Options(val name: String, val count: Int = 1, val label: String? = "a label", val note: String? = null)

    data class Named(@SerializedName("full_name", alternate = ["name"]) val fullName: String)

    data class WithBody(val name: String) {
        var extra: String? = null
    }

    enum class Color {
        RED,

        @SerializedName("blue")
        BLUE,
    }

    data class Line(val sku: String, val quantity: Int = 1)

    data class Order(
        val lines: List<Line>,
        val backup: Line? = null,
        val stock: Map<String, Int>,
        val tags: Set<String>,
        val color: Color,
    )

    data class Page<T>(val items: List<T>, val total: Int)

    data class Catalog(val page: Page<Line>)

    data class Node(val name: String, val children: List<Node>)

    data class Tree(val root: Node)

    data class Item(val name: String)

    object Other {
        data class Item(val code: Int)
    }

    data class TwoItems(val a: Item, val b: Other.Item)

    @Description("A thing to describe")
    data class Described(
        @Description("What people call it") val name: String,
        @Description("The one it came from") val line: Line,
    )

    class AccountId(raw: UUID): Id(raw)

    data class Domain(
        val id: AccountId,
        val uuid: UUID,
        val email: Email,
        val day: LocalDate,
        val at: LocalDateTime,
        val time: LocalTime,
        val month: YearMonth,
        val amount: Money,
    )

    data class Patch(val id: String, val phone: Maybe<String?> = Maybe.None)

    data class Rename(val id: String, val name: Maybe<String> = Maybe.None)

    @JvmInline
    value class Iso(val value: String)

    data class Country(val code: Iso, val region: Iso? = null)

    data class Validated(
        @NotBlank val name: String,
        @Size(min = 2, max = 10) val code: String,
        @NullOrNotBlank val nick: String?,
        @NotEmpty val tags: List<String>,
        @Size(max = 3) val picks: List<String>,
        @Min(1) @Max(5) val count: Int,
        @Positive val positive: Int,
        @PositiveOrZero val zeroOrMore: Int,
        @DecimalMin("0.5") val rate: Double,
        @DecimalMin("0", inclusive = false) val above: Double,
        @Pattern(regexp = "^[0-9]{4}$") val zip: String,
        @jakarta.validation.constraints.Email val contact: String,
    )

    data class OnParameter(@NotBlank val name: String)

    sealed class Config {
        data class Http(val url: String): Config()

        data class Script(val code: String): Config()
    }

    data class Tool(val config: Config)

    data class Key(val value: String)

    data class Code(val value: String)

    data class Locked(val key: Key, val code: Code)

    data class Holder(val locked: Locked)

    private companion object {
        const val DEFS = $$"$defs"
    }
}
