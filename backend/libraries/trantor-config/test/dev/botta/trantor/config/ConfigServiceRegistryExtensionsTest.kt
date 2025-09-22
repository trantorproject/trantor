package dev.botta.trantor.config

import dev.botta.trantor.config.providers.addMemoryCollection
import dev.botta.trantor.serialization.gson.addGsonSerializer
import dev.botta.trantor.serviceProvider.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*

class ConfigServiceRegistryExtensionsTest {
    @Test
    fun `add config section mapped to service`() {
        config.addMemoryCollection(
            "MySettings.key" to "some value",
            "MySettings.other" to "3",
        )
        services.addConfig<MySettings>("MySettings")

        val settings = provider.get<MySettings>()

        assertThat(settings.key).isEqualTo("some value")
        assertThat(settings.other).isEqualTo(3)
    }

    @Test
    fun `add missing config section`() {
        services.addConfig<MySettings>("MySettings")

        val settings = provider.get<MySettings>()

        assertThat(settings.key).isEqualTo("default value")
        assertThat(settings.other).isNull()
    }

    @Test
    fun `change mapped config service value`() {
        config.addMemoryCollection(
            "MySettings.key" to "some value",
            "MySettings.other" to "3",
        )
        services.addConfig<MySettings>("MySettings")
        services.configure<MySettings> { it.key = "overridden" }

        val settings = provider.get<MySettings>()

        assertThat(settings.key).isEqualTo("overridden")
    }

    @BeforeEach
    fun beforeEach() {
        services.addSingleton<Config> { config }
        services.addGsonSerializer()
    }

    private val config = ConfigManager()
    private val services = ServiceRegistry()
    private val provider = DefaultServiceProvider(services)

    class MySettings {
        var key: String = "default value"
        var other: Int? = null
    }
}
