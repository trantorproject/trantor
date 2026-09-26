@file:Suppress("ClassName")

package dev.botta.trantor.web.client

import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.config.providers.addMemoryCollection
import dev.botta.trantor.di.DefaultServiceProvider
import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.serialization.gson.GsonSerializer
import dev.botta.trantor.web.client.okhttp.OkHttpHttpClient
import dev.botta.trantor.web.client.testing.RecordingHttpClient
import dev.botta.trantor.web.client.tracing.TracingHttpClient
import io.opentelemetry.api.OpenTelemetry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ServiceRegistryExtensionsTest {
    @Nested
    inner class `the client` {
        @Test
        fun `is OkHttp, the one that also streams`() {
            registry.addHttpClient()

            assertThat(resolve<HttpClient>()).isInstanceOf(OkHttpHttpClient::class.java)
        }

        @Test
        fun `is built with the settings of the httpClient section`() {
            config.addMemoryCollection("httpClient.requestTimeout" to "5000", "httpClient.followRedirects" to "true")
            registry.addHttpClient()

            val client = resolve<HttpClient>() as OkHttpHttpClient

            assertThat(client.config.requestTimeout).isEqualTo(5000)
            assertThat(client.config.followRedirects).isTrue()
            assertThat(client.config.idleTimeout).isEqualTo(HttpClientSettings().idleTimeout)
        }

        @Test
        fun `is built with what the application set in code`() {
            registry.addHttpClient { settings, _ -> settings.connectTimeout = 2000 }

            assertThat((resolve<HttpClient>() as OkHttpHttpClient).config.connectTimeout).isEqualTo(2000)
        }

        @Test
        fun `is the same one for everybody, so they share its connections`() {
            registry.addHttpClient()
            val services = DefaultServiceProvider(registry)

            assertThat(services.get<HttpClient>()).isSameAs(services.get<HttpClient>())
        }
    }

    @Nested
    inner class `registering it` {
        @Test
        fun `twice is the same as once, and what the second call sets still counts`() {
            registry.addHttpClient()
            registry.addHttpClient { settings, _ -> settings.requestTimeout = 0 }

            assertThat(registry.count { it.serviceType == HttpClient::class.java }).isEqualTo(1)
            assertThat((resolve<HttpClient>() as OkHttpHttpClient).config.requestTimeout).isEqualTo(0)
        }

        @Test
        fun `keeps the client the application registered`() {
            val own = RecordingHttpClient()
            registry.addSingleton<HttpClient>(own)

            registry.addHttpClient()

            assertThat(resolve<HttpClient>()).isSameAs(own)
        }
    }

    @Nested
    inner class `a client with a name` {
        @Test
        fun `has its own section and its own instance`() {
            config.addMemoryCollection("httpClient.ai.idleTimeout" to "120000")
            registry.addHttpClient()
            registry.addHttpClient("ai")
            val services = DefaultServiceProvider(registry)

            val ai = services.get<HttpClient>("ai") as OkHttpHttpClient

            assertThat(ai).isNotSameAs(services.get<HttpClient>())
            assertThat(ai.config.idleTimeout).isEqualTo(120_000)
            assertThat((services.get<HttpClient>() as OkHttpHttpClient).config.idleTimeout)
                .isEqualTo(HttpClientSettings().idleTimeout)
        }

        @Test
        fun `is adjusted in code without touching the others`() {
            registry.addHttpClient()
            registry.addHttpClient("ai") { settings, _ -> settings.requestTimeout = 0 }
            val services = DefaultServiceProvider(registry)

            assertThat((services.get<HttpClient>("ai") as OkHttpHttpClient).config.requestTimeout).isEqualTo(0)
            assertThat((services.get<HttpClient>() as OkHttpHttpClient).config.requestTimeout)
                .isEqualTo(HttpClientSettings().requestTimeout)
        }
    }

    @Nested
    inner class `tracing` {
        @Test
        fun `wraps the client when there is an OpenTelemetry in the container`() {
            registry.addSingleton<OpenTelemetry>(OpenTelemetry.noop())
            registry.addHttpClient()

            assertThat(resolve<HttpClient>()).isInstanceOf(TracingHttpClient::class.java)
        }

        @Test
        fun `costs nothing when there is none`() {
            registry.addHttpClient()

            assertThat(resolve<HttpClient>()).isNotInstanceOf(TracingHttpClient::class.java)
        }
    }

    private inline fun <reified T: Any> resolve() = DefaultServiceProvider(registry).get<T>()

    private val config = ConfigManager()
    private val registry = ServiceRegistry(config).apply { addSingleton<JsonSerializer>(GsonSerializer()) }
}
