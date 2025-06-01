package dev.botta.trantor.config

import dev.botta.trantor.config.providers.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*

class PropertiesResourceConfigProviderTest {
    @Test
    fun `don't fail if resource not found`() {
        assertDoesNotThrow {
            config.addPropertiesResource("invalid.json")
        }
    }

    @Test
    fun `string property`() {
        config.addPropertiesResource("settings.properties")

        assertThat(config["stringProperty"]).isEqualTo("value")
    }

    @Test
    fun `empty property`() {
        config.addPropertiesResource("settings.properties")

        assertThat(config["emptyProperty"]).isEqualTo("")
    }

    @Test
    fun `child property`() {
        config.addPropertiesResource("settings.properties")

        assertThat(config["parent.child"]).isEqualTo("child value")
    }

    private val config = ConfigManager()
}
