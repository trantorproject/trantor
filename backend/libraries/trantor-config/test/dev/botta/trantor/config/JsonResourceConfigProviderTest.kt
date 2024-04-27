package dev.botta.trantor.config

import dev.botta.trantor.config.providers.addJsonResource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*

class JsonResourceConfigProviderTest {
    @Test
    fun `don't fail if resource not found`() {
        assertDoesNotThrow {
            manager.addJsonResource("invalid.json")
        }
    }

    @Test
    fun `json boolean property`() {
        manager.addJsonResource("settings.json")

        assertThat(manager["booleanProperty"]).isEqualTo("true")
    }

    @Test
    fun `json null property`() {
        manager.addJsonResource("settings.json")

        assertThat(manager["nullProperty"]).isNull()
    }

    @Test
    fun `json int property`() {
        manager.addJsonResource("settings.json")

        assertThat(manager["intProperty"]).isEqualTo("3")
    }

    @Test
    fun `json float property`() {
        manager.addJsonResource("settings.json")

        assertThat(manager["floatProperty"]).isEqualTo("3.14")
    }

    @Test
    fun `json string property`() {
        manager.addJsonResource("settings.json")

        assertThat(manager["stringProperty"]).isEqualTo("hello")
    }

    @Test
    fun `json array property`() {
        manager.addJsonResource("settings.json")

        assertThat(manager["arrayProperty.__config_type__"]).isEqualTo("array")
        assertThat(manager["arrayProperty.size"]).isEqualTo("6")
        assertThat(manager["arrayProperty.0"]).isEqualTo("true")
        assertThat(manager["arrayProperty.1"]).isNull()
        assertThat(manager["arrayProperty.2"]).isEqualTo("3")
        assertThat(manager["arrayProperty.3"]).isEqualTo("3.14")
        assertThat(manager["arrayProperty.4"]).isEqualTo("hello")
        assertThat(manager["arrayProperty.5.__config_type__"]).isEqualTo("array")
        assertThat(manager["arrayProperty.5.size"]).isEqualTo("3")
        assertThat(manager["arrayProperty.5.0"]).isEqualTo("1")
        assertThat(manager["arrayProperty.5.1"]).isEqualTo("2")
        assertThat(manager["arrayProperty.5.2"]).isEqualTo("3")
    }

    @Test
    fun `json object property`() {
        manager.addJsonResource("settings.json")

        assertThat(manager["objectProperty.property1"]).isEqualTo("value 1")
        assertThat(manager["objectProperty.property2.__config_type__"]).isEqualTo("array")
        assertThat(manager["objectProperty.property2.size"]).isEqualTo("3")
        assertThat(manager["objectProperty.property2.0"]).isEqualTo("1")
        assertThat(manager["objectProperty.property2.1"]).isEqualTo("2")
        assertThat(manager["objectProperty.property2.2"]).isEqualTo("3")
        assertThat(manager["objectProperty.property3.subproperty1"]).isNull()
        assertThat(manager["objectProperty.property3.subproperty2"]).isEqualTo("sub property value")
        assertThat(manager["objectProperty.property4.__config_type__"]).isEqualTo("array")
        assertThat(manager["objectProperty.property4.size"]).isEqualTo("1")
        assertThat(manager["objectProperty.property4.0.subarray_property1"]).isEqualTo("value1")
    }

    @Test
    fun `override object property`() {
        manager.addJsonResource("settings.json")
        manager.addJsonResource("settings.production.json")

        assertThat(manager["objectProperty.property1"]).isEqualTo("other value 1")
    }

    private val manager = ConfigManager()
}
