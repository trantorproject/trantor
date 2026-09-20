@file:Suppress("ClassName")

package dev.botta.trantor.config.providers

import dev.botta.env.EnvVar
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EnvironmentVariablesConfigProviderTest {
    @Nested
    inner class `the name it came with` {
        @Test
        fun `is always kept`() {
            val provider = providerOf("PORT" to "8080")

            assertThat(provider.get("PORT")).isEqualTo("8080")
        }

        @Test
        fun `is found whatever the case, like every other config key`() {
            val provider = providerOf("PORT" to "8080")

            assertThat(provider.get("port")).isEqualTo("8080")
        }

        @Test
        fun `a variable nobody set is not there`() {
            val provider = providerOf("PORT" to "8080")

            assertThat(provider.get("OTHER")).isNull()
            assertThat(provider.has("OTHER")).isFalse()
        }
    }

    @Nested
    inner class `the path it also answers to` {
        @Test
        fun `a double underscore is a dot`() {
            val provider = providerOf("DB__URL" to "jdbc:postgresql://localhost/app")

            assertThat(provider.get("db.url")).isEqualTo("jdbc:postgresql://localhost/app")
        }

        @Test
        fun `a single underscore makes camel case, matching the settings class`() {
            val provider = providerOf("DB__CONNECTION_STRING" to "postgres")

            assertThat(provider.get("db.connectionString")).isEqualTo("postgres")
        }

        @Test
        fun `several words in one part all join up`() {
            val provider = providerOf("HTTP_SERVER__MAX_REQUEST_SIZE_IN_MB" to "5")

            assertThat(provider.get("httpServer.maxRequestSizeInMb")).isEqualTo("5")
        }

        @Test
        fun `nesting goes as deep as it is written`() {
            val provider = providerOf("AI__PROVIDERS__OPEN_AI__API_KEY" to "sk-123")

            assertThat(provider.get("ai.providers.openAi.apiKey")).isEqualTo("sk-123")
        }

        @Test
        fun `a name with no underscore has only itself`() {
            val provider = providerOf("PORT" to "8080")

            assertThat(provider.paths).containsExactly("PORT")
        }

        @Test
        fun `the original name survives alongside the path`() {
            val provider = providerOf("DB__URL" to "postgres")

            assertThat(provider.get("DB__URL")).isEqualTo("postgres")
            assertThat(provider.get("db.url")).isEqualTo("postgres")
        }
    }

    @Nested
    inner class `a prefix` {
        @Test
        fun `is taken off before the name is read`() {
            val provider = providerOf("TRANTOR_", "TRANTOR_DB__URL" to "postgres")

            assertThat(provider.get("db.url")).isEqualTo("postgres")
        }

        @Test
        fun `leaves out everything that does not carry it`() {
            val provider = providerOf("TRANTOR_", "TRANTOR_PORT" to "8080", "PATH" to "/usr/bin")

            assertThat(provider.paths).containsExactly("PORT")
        }

        @Test
        fun `does not care about case either`() {
            val provider = providerOf("trantor_", "TRANTOR_PORT" to "8080")

            assertThat(provider.get("PORT")).isEqualTo("8080")
        }
    }

    @Nested
    inner class `sections` {
        @Test
        fun `a name with a path below it is a section`() {
            val provider = providerOf("DB__URL" to "postgres")

            assertThat(provider.hasSection("db")).isTrue()
        }

        @Test
        fun `a name nobody set is not a section`() {
            val provider = providerOf("DB__URL" to "postgres")

            assertThat(provider.hasSection("cache")).isFalse()
        }
    }

    private fun providerOf(vararg variables: Pair<String, String>) = providerOf("", *variables)

    private fun providerOf(prefix: String, vararg variables: Pair<String, String>) =
        EnvironmentVariablesConfigProvider(prefix) { variables.map { EnvVar(it.first, it.second) } }
            .apply { load() }
}
