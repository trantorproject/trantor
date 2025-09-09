package dev.botta.trantor.serialization.gson.adapters

import com.google.gson.GsonBuilder
import dev.botta.json.Json
import dev.botta.trantor.core.lang.Maybe
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*

class MaybeSerializerTest {
    @Test
    fun `deserialize with value`() {
        val json = Json.obj("dataInt" to 1)

        val result = builder.create().fromJson(json.toString(), Subject::class.java)

        assertThat(result.dataInt.hasValue()).isTrue
        assertThat((result.dataInt as? Maybe.Value)?.value).isEqualTo(1)
    }

    @Test
    fun `deserialize none if missing value`() {
        val json = Json.obj("missing" to 1)

        val result = builder.create().fromJson(json.toString(), Subject::class.java)

        assertThat(result.dataInt.hasValue()).isFalse
        assertThat(result.dataInt.isNone()).isTrue
    }

    @Test
    fun `deserialize null`() {
        val json = Json.obj("dataNullableInt" to null)

        val result = builder.create().fromJson(json.toString(), Subject2::class.java)

        assertThat(result.dataNullableInt.hasValue()).isTrue
        assertThat((result.dataNullableInt as? Maybe.Value)?.value).isNull()
    }

    @Test
    fun `deserialize with value in nullable`() {
        val json = Json.obj("dataNullableInt" to 1)

        val result = builder.create().fromJson(json.toString(), Subject2::class.java)

        assertThat(result.dataNullableInt.hasValue()).isTrue
        assertThat((result.dataNullableInt as? Maybe.Value)?.value).isEqualTo(1)
    }

    @Test
    fun `deserialize none if missing value in nullable`() {
        val json = Json.obj("missing" to 1)

        val result = builder.create().fromJson(json.toString(), Subject2::class.java)

        assertThat(result.dataNullableInt.hasValue()).isFalse
        assertThat(result.dataNullableInt.isNone()).isTrue
    }

    @Test
    fun `serialize with value`() {
        val obj = Subject(Maybe.of(1))

        val result = builder.create().toJson(obj)
        val json = Json.parse(result)

        assertThat(json.isObject).isTrue
        assertThat(json.asObject()?.get("dataInt")?.isNumber).isTrue
        assertThat(json.asObject()?.get("dataInt")?.asInt()).isEqualTo(1)
    }

    @Test
    fun `serialize with none`() {
        val obj = Subject(Maybe.None)

        val result = builder.create().toJson(obj)
        val json = Json.parse(result)

        assertThat(json.isObject).isTrue
        assertThat(json.asObject()?.containsKey("dataInt")).isFalse
    }

    @Test
    fun `serialize with value in nullable`() {
        val obj = Subject2(Maybe.of(1))

        val result = builder.create().toJson(obj)
        val json = Json.parse(result)

        assertThat(json.isObject).isTrue
        assertThat(json.asObject()?.get("dataNullableInt")?.isNumber).isTrue
        assertThat(json.asObject()?.get("dataNullableInt")?.asInt()).isEqualTo(1)
    }

    @Test
    fun `serialize with none in nullable`() {
        val obj = Subject2(Maybe.None)

        val result = builder.create().toJson(obj)
        val json = Json.parse(result)

        assertThat(json.isObject).isTrue
        assertThat(json.asObject()?.containsKey("dataNullableInt")).isFalse
    }

    @Test
    fun `serialize with null in nullable`() {
        val obj = Subject2(Maybe.of(null))

        val result = builder.create().toJson(obj)
        val json = Json.parse(result)

        assertThat(json.isObject).isTrue
        assertThat(json.asObject()?.containsKey("dataNullableInt")).isFalse
    }

    @BeforeEach
    fun beforeEach() {
        builder.registerTypeAdapterFactory(MaybeTypeAdapterFactory())
    }

    private val builder: GsonBuilder = GsonBuilder()

    class Subject(val dataInt: Maybe<Int> = Maybe.None)

    class Subject2(val dataNullableInt: Maybe<Int?> = Maybe.None)
}
