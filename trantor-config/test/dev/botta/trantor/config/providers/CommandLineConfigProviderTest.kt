@file:Suppress("ClassName")

package dev.botta.trantor.config.providers

import dev.botta.trantor.config.ConfigManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CommandLineConfigProviderTest {
    @Nested
    inner class `an argument` {
        @Test
        fun `with two dashes and an equals sign is a key and its value`() {
            val provider = providerOf("--httpServer.port=9000")

            assertThat(provider.get("httpServer.port")).isEqualTo("9000")
        }

        @Test
        fun `with two dashes takes the next one as its value`() {
            val provider = providerOf("--env", "development")

            assertThat(provider.get("env")).isEqualTo("development")
        }

        @Test
        fun `without dashes is a key and its value too, when it has an equals sign`() {
            val provider = providerOf("env=development")

            assertThat(provider.get("env")).isEqualTo("development")
        }

        @Test
        fun `keeps whatever follows the first equals sign`() {
            val provider = providerOf("--db.url=jdbc:postgresql://localhost/app?ssl=true")

            assertThat(provider.get("db.url")).isEqualTo("jdbc:postgresql://localhost/app?ssl=true")
        }

        @Test
        fun `can set a key to nothing`() {
            val provider = providerOf("--appName=")

            assertThat(provider.get("appName")).isEmpty()
        }

        @Test
        fun `is found whatever the case, like every other config key`() {
            val provider = providerOf("--httpServer.port=9000")

            assertThat(provider.get("HTTPSERVER.PORT")).isEqualTo("9000")
        }
    }

    @Nested
    inner class `a switch` {
        @Test
        fun `with nothing after it is true`() {
            val provider = providerOf("--verbose")

            assertThat(provider.get("verbose")).isEqualTo("true")
        }

        @Test
        fun `followed by another switch is true, and the other one is read on its own`() {
            val provider = providerOf("--verbose", "--env", "staging")

            assertThat(provider.get("verbose")).isEqualTo("true")
            assertThat(provider.get("env")).isEqualTo("staging")
        }
    }

    @Nested
    inner class `what is left alone` {
        @Test
        fun `an argument with no dashes and no equals sign, so the application can have its own`() {
            val provider = providerOf("migrate", "--env=staging")

            assertThat(provider.paths).containsExactly("env")
        }

        @Test
        fun `a single dash, which is how a short option of the application looks`() {
            val provider = providerOf("-v", "-p=9000")

            assertThat(provider.paths).isEmpty()
        }

        @Test
        fun `a value taken by the switch before it is not read again`() {
            val provider = providerOf("--message", "a=b")

            assertThat(provider.paths).containsExactly("message")
            assertThat(provider.get("message")).isEqualTo("a=b")
        }
    }

    @Test
    fun `the last time a key is given is the one that counts`() {
        val provider = providerOf("--env=staging", "--env=production")

        assertThat(provider.get("env")).isEqualTo("production")
    }

    @Test
    fun `what the command line says wins over what was added before it`() {
        val config = ConfigManager()
            .addMemoryCollection("httpServer.port" to "8080")
            .addCommandLine(arrayOf("--httpServer.port=9000"))

        assertThat(config["httpServer.port"]).isEqualTo("9000")
    }

    private fun providerOf(vararg args: String) = CommandLineConfigProvider(arrayOf(*args)).apply { load() }
}
