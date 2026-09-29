package dev.botta.trantor.serialization.gson

import dev.botta.json.Json
import dev.botta.trantor.serialization.gson.adapters.StringValueSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GsonSerializerTest {
    @Test
    fun `data classes`() {
        val json = Json.obj(
            "property" to false,
            "readOnlyProperty" to "some value",
            "aNumber" to "5",
            "aDouble" to "5.4",
        ).toString()

        val obj = serializer.deserialize(json, MyDataClass::class.java)

        assertThat(obj.property).isFalse()
        assertThat(obj.readOnlyProperty).isEqualTo("some value")
        assertThat(obj.missingPropertyWithDefault).isEqualTo(3)
        assertThat(obj.aNumber).isEqualTo(5)
        assertThat(obj.aDouble).isEqualTo(5.4)
    }

    @Test
    fun `classes`() {
        val json = Json.obj(
            "aNumber" to "5",
            "aDouble" to "5.4",
        ).toString()

        val obj = serializer.deserialize(json, MyClass::class.java)

        assertThat(obj.aNumber).isEqualTo(5)
        assertThat(obj.aDouble).isEqualTo(5.4)
    }

    @Test
    fun `an adapter registered after the serializer was used is used too`() {
        serializer.serialize(Tag("a"))

        serializer.registerTypeAdapter(Tag::class.java, StringValueSerializer({ Tag(it) }, { it.value }))

        assertThat(serializer.serialize(Tag("a"))).isEqualTo("\"a\"")
    }

    @Test
    fun `the Gson it gives is the one it uses`() {
        serializer.registerTypeAdapter(Tag::class.java, StringValueSerializer({ Tag(it) }, { it.value }))

        assertThat(serializer.getGson()).isSameAs(serializer.getGson())
        assertThat(serializer.getGson().toJson(Tag("a"))).isEqualTo("\"a\"")
    }

    private val serializer = GsonSerializer()

    data class Tag(val value: String)

    class MyClass {
        var aNumber: Int? = null
        var aDouble: Double? = null
    }

    data class MyDataClass(
        var property: Boolean = true,
        val readOnlyProperty: String,
        val missingPropertyWithDefault: Int = 3,
        var aNumber: Int = 2,
        var aDouble: Double = 2.0,
    )
}

