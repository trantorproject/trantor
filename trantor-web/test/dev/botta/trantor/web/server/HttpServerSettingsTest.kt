@file:Suppress("ClassName")

package dev.botta.trantor.web.server

import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.config.providers.addMemoryCollection
import dev.botta.trantor.di.DefaultServiceProvider
import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.serialization.gson.GsonSerializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class HttpServerSettingsTest {
    @Nested
    inner class `the port` {
        @Test
        fun `is 8080 when configuration says nothing, which a process without root can open`() {
            assertThat(settingsOf(ConfigManager()).port).isEqualTo(8080)
        }

        @Test
        fun `is the one configuration says`() {
            val config = ConfigManager().addMemoryCollection("httpServer.port" to "9000")

            assertThat(settingsOf(config).port).isEqualTo(9000)
        }
    }

    private fun settingsOf(config: ConfigManager): HttpServerSettings {
        val registry = ServiceRegistry(config).apply {
            addSingleton<JsonSerializer>(GsonSerializer())
            addHttpServer()
        }

        return DefaultServiceProvider(registry).get<HttpServerSettings>()
    }
}
