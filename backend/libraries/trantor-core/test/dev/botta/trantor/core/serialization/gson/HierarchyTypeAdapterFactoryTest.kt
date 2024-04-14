package dev.botta.trantor.core.serialization.gson

import com.google.gson.*
import com.proyecto404.finanz.modules.common.base.serialization.gson.HierarchyTypeAdapterFactory
import dev.botta.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*

class HierarchyTypeAdapterFactoryTest {
    @Test
    fun `fromJson fails with an unregistered child class`() {
        builder.registerTypeAdapterFactory(HierarchyTypeAdapterFactory.of(Base::class.java))
        val json = Json.obj("data" to mapOf("type" to "Child1"))

        assertThrows<JsonParseException> { builder.create().fromJson(json.toString(), Subject::class.java) }
    }

    @Test
    fun `fromJson deserializes child class`() {
        builder.registerTypeAdapterFactory(HierarchyTypeAdapterFactory.of(Base::class.java)
            .subtype(Child1::class.java)
        )
        val json = Json.obj("data" to mapOf("type" to "Child1"))

        val instance = builder.create().fromJson(json.toString(), Subject::class.java)

        assertThat(instance.data).isInstanceOf(Child1::class.java)
    }

    @Test
    fun `fromJson deserializes child class data`() {
        builder.registerTypeAdapterFactory(HierarchyTypeAdapterFactory.of(Base::class.java)
            .subtype(Child2::class.java)
        )
        val json = Json.obj("data" to mapOf("type" to "Child2", "someData" to "1234"))

        val instance = builder.create().fromJson(json.toString(), Subject::class.java)

        assertThat(instance.data).isInstanceOf(Child2::class.java)
        assertThat((instance.data as Child2).someData).isEqualTo("1234")
    }

    @Test
    fun `fromJson deserializes child class data when referencing class directly`() {
        builder.registerTypeAdapterFactory(HierarchyTypeAdapterFactory.of(Base::class.java)
            .subtype(Child2::class.java)
        )
        val json = Json.obj("type" to "Child2", "someData" to "1234")

        val instance = builder.create().fromJson(json.toString(), Child2::class.java)

        assertThat(instance).isInstanceOf(Child2::class.java)
        assertThat((instance as Child2).someData).isEqualTo("1234")
    }

    @Test
    fun `toJson fails with an unregistered child class`() {
        builder.registerTypeAdapterFactory(HierarchyTypeAdapterFactory.of(Base::class.java))
        val subject = Subject(Child1())

        assertThrows<JsonParseException> { builder.create().toJson(subject) }
    }

    @Test
    fun `toJson serializes child classes with data`() {
        builder.registerTypeAdapterFactory(HierarchyTypeAdapterFactory.of(Base::class.java)
            .subtype(Child2::class.java)
        )
        val subject = Subject(Child2("1234"))

        val result = builder.create().toJson(subject)

        val json = Json.parse(result)
        assertThat(json.path("data.type")?.asString()).isEqualTo("Child2")
        assertThat(json.path("data.someData")?.asString()).isEqualTo("1234")
    }

    @BeforeEach
    fun beforeEach() {
        builder.registerTypeAdapterFactory(NullableTypeAdapterFactory())
    }

    private val builder: GsonBuilder = GsonBuilder()

    class Subject(val data: Base)

    abstract class Base
    class Child1: Base()
    class Child2(val someData: String): Base()
}
