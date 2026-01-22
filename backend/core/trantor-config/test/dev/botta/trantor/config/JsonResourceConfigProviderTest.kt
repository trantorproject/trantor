package dev.botta.trantor.config

import dev.botta.trantor.config.providers.addJsonResource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*

class JsonResourceConfigProviderTest {
    @Test
    fun `don't fail if resource not found`() {
        assertDoesNotThrow {
            config.addJsonResource("invalid.json")
        }
    }

    @Test
    fun `json boolean property`() {
        config.addJsonResource("settings.json")

        assertThat(config["booleanProperty"]).isEqualTo("true")
    }

    @Test
    fun `json null property`() {
        config.addJsonResource("settings.json")

        assertThat(config["nullProperty"]).isNull()
    }

    @Test
    fun `json int property`() {
        config.addJsonResource("settings.json")

        assertThat(config["intProperty"]).isEqualTo("3")
    }

    @Test
    fun `json float property`() {
        config.addJsonResource("settings.json")

        assertThat(config["floatProperty"]).isEqualTo("3.14")
    }

    @Test
    fun `json string property`() {
        config.addJsonResource("settings.json")

        assertThat(config["stringProperty"]).isEqualTo("hello")
    }

    @Test
    fun `json array property`() {
        config.addJsonResource("settings.json")

        assertThat(config["arrayProperty.__config_type__"]).isEqualTo("array")
        assertThat(config["arrayProperty.size"]).isEqualTo("6")
        assertThat(config["arrayProperty.0"]).isEqualTo("true")
        assertThat(config["arrayProperty.1"]).isNull()
        assertThat(config["arrayProperty.2"]).isEqualTo("3")
        assertThat(config["arrayProperty.3"]).isEqualTo("3.14")
        assertThat(config["arrayProperty.4"]).isEqualTo("hello")
        assertThat(config["arrayProperty.5.__config_type__"]).isEqualTo("array")
        assertThat(config["arrayProperty.5.size"]).isEqualTo("3")
        assertThat(config["arrayProperty.5.0"]).isEqualTo("1")
        assertThat(config["arrayProperty.5.1"]).isEqualTo("2")
        assertThat(config["arrayProperty.5.2"]).isEqualTo("3")
    }

    @Test
    fun `json object property`() {
        config.addJsonResource("settings.json")

        assertThat(config["objectProperty.property1"]).isEqualTo("value 1")
        assertThat(config["objectProperty.property2.__config_type__"]).isEqualTo("array")
        assertThat(config["objectProperty.property2.size"]).isEqualTo("3")
        assertThat(config["objectProperty.property2.0"]).isEqualTo("1")
        assertThat(config["objectProperty.property2.1"]).isEqualTo("2")
        assertThat(config["objectProperty.property2.2"]).isEqualTo("3")
        assertThat(config["objectProperty.property3.subproperty1"]).isNull()
        assertThat(config["objectProperty.property3.subproperty2"]).isEqualTo("sub property value")
        assertThat(config["objectProperty.property4.__config_type__"]).isEqualTo("array")
        assertThat(config["objectProperty.property4.size"]).isEqualTo("1")
        assertThat(config["objectProperty.property4.0.subarray_property1"]).isEqualTo("value1")
    }

    @Test
    fun `override object property`() {
        config.addJsonResource("settings.json")
        config.addJsonResource("settings.production.json")

        assertThat(config["objectProperty.property1"]).isEqualTo("other value 1")
    }

    private val config = ConfigManager()
}
