package dev.botta.trantor.serviceProvider

import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.config.providers.addMemoryCollection
import dev.botta.trantor.serialization.gson.addGsonSerializer
import dev.botta.trantor.serviceProvider.DefaultServiceProviderTest.MyService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*

class ConfigServiceRegistryExtensionsTest {
    @Test
    fun `add config section mapped to service`() {
        config.addMemoryCollection(
            "MySettings:key" to "some value",
            "MySettings:other" to "3",
        )
        services.addGsonSerializer()
        services.configure<MySettings>(config.getSection("MySettings"))

        val settings = provider.get<MySettings>()

        assertThat(settings.key).isEqualTo("some value")
        assertThat(settings.other).isEqualTo(3)
    }

    @Test
    fun `change mapped config service value`() {
        config.addMemoryCollection(
            "MySettings:key" to "some value",
            "MySettings:other" to "3",
        )
        services.addGsonSerializer()
        services.configure<MySettings>(config.getSection("MySettings"))
        services.configure<MySettings> { it.key = "overridden" }

        val settings = provider.get<MySettings>()

        assertThat(settings.key).isEqualTo("overridden")
    }

    private val config = ConfigManager()
    private val services = ServiceRegistry()
    private val provider = DefaultServiceProvider(services)

    class MySettings {
        var key: String = "default value"
        var other: Int? = null
    }
}
