package dev.botta.trantor.config

import dev.botta.trantor.config.providers.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*

class PropertiesResourceConfigProviderTest {
    @Test
    fun `don't fail if resource not found`() {
        assertDoesNotThrow {
            manager.addPropertiesResource("invalid.json")
        }
    }

    @Test
    fun `string property`() {
        manager.addPropertiesResource("settings.properties")

        assertThat(manager["stringProperty"]).isEqualTo("value")
    }

    @Test
    fun `empty property`() {
        manager.addPropertiesResource("settings.properties")

        assertThat(manager["emptyProperty"]).isEqualTo("")
    }

    @Test
    fun `child property`() {
        manager.addPropertiesResource("settings.properties")

        assertThat(manager["parent.child"]).isEqualTo("child value")
    }

    private val manager = ConfigManager()
}
