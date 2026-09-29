@file:Suppress("ClassName")

package dev.botta.trantor.serialization.gson

import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import dev.botta.json.Json
import dev.botta.trantor.domain.Id
import dev.botta.trantor.domain.Money
import dev.botta.trantor.primitives.lang.Maybe
import dev.botta.trantor.serialization.gson.adapters.HierarchyTypeAdapterFactory
import dev.botta.trantor.serialization.gson.adapters.StringValueSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * How the serializer reads and writes the Kotlin classes of an application: what a schema of them has to describe.
 */
class GsonSerializerKotlinTest {
    @Nested
    inner class `reading a class` {
        @Test
        fun `calls the primary constructor, so a field left out takes its default`() {
            val read = serializer.deserialize<Options>("""{"name":"a"}""")

            assertThat(read).isEqualTo(Options("a", count = 1, label = "a label", note = null))
        }

        @Test
        fun `a default can depend on another parameter`() {
            assertThat(serializer.deserialize<Derived>("""{"a":"x"}""").b).isEqualTo("x!")
        }

        @Test
        fun `a nullable field sent as null is null, even when its default is not`() {
            assertThat(serializer.deserialize<Options>("""{"name":"a","label":null}""").label).isNull()
        }

        @Test
        fun `a field that is required fails when it is missing or null, naming it`() {
            assertThatThrownBy { serializer.deserialize<Options>("""{"count":2}""") }
                .isInstanceOf(JsonParseException::class.java)
                .hasMessageContaining("name cannot be null in type 'Options'")
            assertThatThrownBy { serializer.deserialize<Options>("""{"name":null}""") }
                .isInstanceOf(JsonParseException::class.java)
                .hasMessageContaining("name cannot be null in type 'Options'")
        }

        @Test
        fun `a field that cannot be null sent as null takes its default, as if it were left out`() {
            assertThat(serializer.deserialize<Options>("""{"name":"a","count":null}""").count).isEqualTo(1)
        }

        @Test
        fun `a primitive without a default is required too`() {
            assertThatThrownBy { serializer.deserialize<Measure>("""{"unit":"kg"}""") }
                .isInstanceOf(JsonParseException::class.java)
                .hasMessageContaining("value cannot be null in type 'Measure'")
        }

        @Test
        fun `a private constructor is called all the same`() {
            assertThat(serializer.deserialize<Secret>("""{"name":"a"}""")).isEqualTo(Secret.of("a"))
        }

        @Test
        fun `the init block runs, and what it throws comes out as it is`() {
            assertThatThrownBy { serializer.deserialize<Positive>("""{"amount":-1}""") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("amount cannot be negative")
        }

        @Test
        fun `a field goes by its serialized name, or any of its alternates`() {
            assertThat(serializer.deserialize<Named>("""{"full_name":"a"}""").fullName).isEqualTo("a")
            assertThat(serializer.deserialize<Named>("""{"name":"a"}""").fullName).isEqualTo("a")
        }

        @Test
        fun `a field that the class does not have is skipped`() {
            assertThat(serializer.deserialize<Options>("""{"name":"a","other":1}""").name).isEqualTo("a")
        }

        @Test
        fun `nested classes, lists, maps and enums are read with their own types`() {
            val json = """{"color":"RED","tags":["a"],"scores":{"a":1},"items":[{"name":"i"}]}"""

            val read = serializer.deserialize<Basket>(json)

            assertThat(read).isEqualTo(Basket(Color.RED, listOf("a"), mapOf("a" to 1), listOf(Item("i"))))
        }

        @Test
        fun `a generic class takes the type it is declared with`() {
            val read = serializer.deserialize<Catalog>("""{"page":{"items":[{"name":"i"}],"total":1}}""")

            assertThat(read.page.items.single()).isEqualTo(Item("i"))
        }

        @Test
        fun `a whole number written with decimals, or as a string, is still a number`() {
            assertThat(serializer.deserialize<Options>("""{"name":"a","count":3.0}""").count).isEqualTo(3)
            assertThat(serializer.deserialize<Options>("""{"name":"a","count":"4"}""").count).isEqualTo(4)
        }

        @Test
        fun `the name of a field is read ignoring case`() {
            assertThat(serializer.deserialize<Options>("""{"NAME":"a"}""").name).isEqualTo("a")
        }

        @Test
        fun `a number with a fraction for a whole number fails`() {
            assertThatThrownBy { serializer.deserialize<Options>("""{"name":"a","count":3.5}""") }
                .isInstanceOf(JsonParseException::class.java)
        }

        @Test
        fun `a property of the body with a field is read after the constructor runs`() {
            assertThat(serializer.deserialize<WithBody>("""{"name":"a","extra":"x"}""").extra).isEqualTo("x")
        }

        @Test
        fun `a number sent for a string is the text of the number`() {
            assertThat(serializer.deserialize<Options>("""{"name":5}""").name).isEqualTo("5")
        }

        @Test
        fun `a boolean is true or false, also written as a string`() {
            assertThat(serializer.deserialize<Switch>("""{"on":false}""").on).isFalse()
            assertThat(serializer.deserialize<Switch>("""{"on":"true"}""").on).isTrue()
            assertThat(serializer.deserialize<Switch>("""{"on":"FALSE"}""").on).isFalse()
        }

        @Test
        fun `a boolean sent as any other string fails, instead of reading as false`() {
            assertThatThrownBy { serializer.deserialize<Switch>("""{"on":"yes"}""") }
                .isInstanceOf(JsonParseException::class.java)
                .hasMessageContaining("'yes'")
            assertThatThrownBy { serializer.deserialize<Switch>("""{"on":true,"maybe":"no"}""") }
                .isInstanceOf(JsonParseException::class.java)
                .hasMessageContaining("'no'")
        }

        @Test
        fun `a null inside a list or a map that cannot hold one fails, saying where`() {
            val list = """{"color":"RED","tags":["a",null],"scores":{},"items":[]}"""
            val map = """{"color":"RED","tags":[],"scores":{"a":null},"items":[]}"""

            assertThatThrownBy { serializer.deserialize<Basket>(list) }
                .isInstanceOf(JsonParseException::class.java)
                .hasMessageContaining("tags[1] cannot be null in type 'Basket'")
            assertThatThrownBy { serializer.deserialize<Basket>(map) }
                .isInstanceOf(JsonParseException::class.java)
                .hasMessageContaining("scores[a] cannot be null in type 'Basket'")
        }

        @Test
        fun `a null inside a list that can hold one stays`() {
            assertThat(serializer.deserialize<Notes>("""{"lines":["a",null]}""").lines).containsExactly("a", null)
        }

        @Test
        fun `an enum that is not one of its values fails naming them`() {
            val json = """{"color":"red","tags":[],"scores":{},"items":[]}"""

            assertThatThrownBy { serializer.deserialize<Basket>(json) }
                .isInstanceOf(JsonParseException::class.java)
                .hasMessageContaining("'red' is not one of RED, BLUE")
        }

        @Test
        fun `an enum goes by the serialized name of each value`() {
            assertThat(serializer.deserialize<Shipping>("""{"speed":"express"}""").speed).isEqualTo(Speed.FAST)
            assertThat(serializer.serialize(Shipping(Speed.FAST))).isEqualTo("""{"speed":"express"}""")
        }

        @Test
        fun `a value class is read from its value, and written as it`() {
            assertThat(serializer.deserialize<Country>("""{"code":"AR"}""").code).isEqualTo(Code("AR"))
            assertThat(serializer.serialize(Country(Code("AR")))).isEqualTo("""{"code":"AR"}""")
        }

        @Test
        fun `a value class can be nullable, and stand on its own`() {
            assertThat(serializer.deserialize<Country>("""{"code":"AR","region":null}""").region).isNull()
            assertThat(serializer.deserialize<Code>(""""UY"""")).isEqualTo(Code("UY"))
            assertThat(serializer.serialize(Code("UY"))).isEqualTo(""""UY"""")
        }
    }

    @Nested
    inner class `reading the types of the domain` {
        @Test
        fun `an id is its uuid, whatever its kind`() {
            val read = serializer.deserialize<Account>("""{"id":"${UUID(0, 1)}","balance":"10.50"}""")

            assertThat(read.id).isEqualTo(AccountId(UUID(0, 1)))
        }

        @Test
        fun `money is read from a string or from a number`() {
            assertThat(serializer.deserialize<Account>("""{"id":"${UUID(0, 1)}","balance":"10.50"}""").balance)
                .isEqualTo(Money("10.50"))
            assertThat(serializer.deserialize<Account>("""{"id":"${UUID(0, 1)}","balance":10.5}""").balance)
                .isEqualTo(Money("10.5"))
        }

        @Test
        fun `a value object of the application is read by the adapter it registered`() {
            val serializer = GsonSerializer().apply {
                registerTypeAdapter(Key::class.java, StringValueSerializer({ Key(it) }, { it.value }))
            }

            assertThat(serializer.deserialize<Locked>("""{"key":"k1"}""").key).isEqualTo(Key("k1"))
        }

        @Test
        fun `an id, an amount or a date that cannot be read fail as input that cannot be read`() {
            assertThatThrownBy { serializer.deserialize<Account>("""{"id":"123","balance":"1"}""") }
                .isInstanceOf(JsonParseException::class.java)
                .hasMessageContaining("'123'")
            assertThatThrownBy { serializer.deserialize<Account>("""{"id":"${UUID(0, 1)}","balance":"ten"}""") }
                .isInstanceOf(JsonParseException::class.java)
                .hasMessageContaining("'ten'")
            assertThatThrownBy { serializer.deserialize<Dated>("""{"day":"2026-09-28T10:00:00"}""") }
                .isInstanceOf(JsonParseException::class.java)
                .hasMessageContaining("'2026-09-28T10:00:00'")
        }
    }

    @Nested
    inner class `reading a hierarchy` {
        @Test
        fun `each object is the subtype its label names, also inside a list`() {
            val json = """{"configs":[{"type":"http","url":"u"},{"type":"script","code":"c"}]}"""

            val read = hierarchies.deserialize<Configs>(json)

            assertThat(read.configs).containsExactly(Config.Http("u"), Config.Script("c"))
        }

        @Test
        fun `a label it does not know fails naming it, and the ones it knows`() {
            assertThatThrownBy { hierarchies.deserialize<Configs>("""{"configs":[{"type":"ftp"}]}""") }
                .isInstanceOf(JsonParseException::class.java)
                .hasMessageContaining("subtype named ftp")
                .hasMessageContaining("http, script")
        }

        @Test
        fun `an object without a label fails saying so`() {
            assertThatThrownBy { hierarchies.deserialize<Configs>("""{"configs":[{"url":"u"}]}""") }
                .isInstanceOf(JsonParseException::class.java)
                .hasMessageContaining("does not define a field named type")
        }
    }

    @Nested
    inner class `writing a class` {
        @Test
        fun `a null field is left out`() {
            assertThat(serializer.serialize(Options("a", label = null))).isEqualTo("""{"name":"a","count":1}""")
        }

        @Test
        fun `the properties of the body with a field are written, and the computed ones are not`() {
            val written = Json.parse(serializer.serialize(WithBody("a").apply { extra = "x" })).asObject()!!

            assertThat(written.keys).containsExactlyInAnyOrder("name", "extra", "fixed")
        }

        @Test
        fun `a Maybe that is None is left out, and a Value of null is written as null`() {
            assertThat(serializer.serialize(Patch("x"))).isEqualTo("""{"id":"x"}""")
            assertThat(serializer.serialize(Patch("x", Maybe.Value(null)))).isEqualTo("""{"id":"x","name":null}""")
        }

        @Test
        fun `a subtype of a hierarchy is written with its label`() {
            assertThat(hierarchies.serialize(Configs(listOf(Config.Http("u")))))
                .isEqualTo("""{"configs":[{"type":"http","url":"u"}]}""")
        }

        @Test
        fun `a field marked Transient is not written`() {
            assertThat(serializer.serialize(WithTransient("a", "cached"))).isEqualTo("""{"name":"a"}""")
        }

        @Test
        fun `a property by lazy is not written, since what its field holds is the delegate`() {
            assertThat(serializer.serialize(WithLazy("a"))).isEqualTo("""{"name":"a"}""")
        }
    }

    data class Switch(val on: Boolean, val maybe: Boolean? = null)

    data class Notes(val lines: List<String?>)

    enum class Speed {
        @SerializedName("express")
        FAST,
        SLOW,
    }

    data class Shipping(val speed: Speed)

    @JvmInline
    value class Code(val value: String)

    data class Country(val code: Code, val region: Code? = null)

    data class Dated(val day: LocalDate)

    data class WithTransient(val name: String, @Transient val cache: String? = null)

    data class WithLazy(val name: String) {
        val upper by lazy { name.uppercase() }
    }

    data class Options(val name: String, val count: Int = 1, val label: String? = "a label", val note: String? = null)

    data class Derived(val a: String, val b: String = "$a!")

    data class Measure(val value: Double, val unit: String)

    data class Secret private constructor(val name: String) {
        companion object {
            fun of(name: String) = Secret(name)
        }
    }

    data class Positive(val amount: Int) {
        init {
            require(amount >= 0) { "amount cannot be negative" }
        }
    }

    data class Named(@SerializedName("full_name", alternate = ["name"]) val fullName: String)

    enum class Color { RED, BLUE }

    data class Item(val name: String, val count: Int = 0)

    data class Basket(val color: Color, val tags: List<String>, val scores: Map<String, Int>, val items: List<Item>)

    data class Page<T>(val items: List<T>, val total: Int)

    data class Catalog(val page: Page<Item>)

    class AccountId(raw: UUID): Id(raw)

    data class Account(val id: AccountId, val balance: Money)

    data class Key(val value: String)

    data class Locked(val key: Key)

    sealed class Config {
        data class Http(val url: String): Config()

        data class Script(val code: String): Config()
    }

    data class Configs(val configs: List<Config>)

    data class WithBody(val name: String) {
        var extra: String? = null
        val computed get() = "computed from $name"
        val fixed: String = "fixed in the body"
    }

    data class Patch(val id: String, val name: Maybe<String?> = Maybe.None)

    private val serializer = GsonSerializer()

    private val hierarchies = GsonSerializer().apply {
        registerTypeAdapterFactory(
            HierarchyTypeAdapterFactory.of<Config>().subtype<Config.Http>("http").subtype<Config.Script>("script"),
        )
    }
}
