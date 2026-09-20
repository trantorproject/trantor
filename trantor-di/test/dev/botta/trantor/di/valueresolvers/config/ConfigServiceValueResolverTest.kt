@file:Suppress("ClassName")

package dev.botta.trantor.di.valueresolvers.config

import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.config.RequiredConfigError
import dev.botta.trantor.config.providers.addMemoryCollection
import dev.botta.trantor.di.DefaultServiceProvider
import dev.botta.trantor.di.ServiceRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ConfigServiceValueResolverTest {
    @Nested
    inner class `a value straight from the configuration` {
        @Test
        fun `is given to the parameter that asked for it`() {
            config.addMemoryCollection("app.name" to "trantor")

            assertThat(provider.create<WantsAName>().name).isEqualTo("trantor")
        }

        @Test
        fun `is found by path, whatever the case`() {
            config.addMemoryCollection("APP.NAME" to "trantor")

            assertThat(provider.create<WantsAName>().name).isEqualTo("trantor")
        }
    }

    @Nested
    inner class `converting what the configuration says` {
        @Test
        fun `configuration is always text, so a number is parsed`() {
            config.addMemoryCollection(
                "app.port" to "8080",
                "app.maxSize" to "9999999999",
                "app.ratio" to "0.75",
                "app.factor" to "1.5",
            )

            val wanted = provider.create<WantsNumbers>()

            assertThat(wanted.port).isEqualTo(8080)
            assertThat(wanted.maxSize).isEqualTo(9_999_999_999L)
            assertThat(wanted.ratio).isEqualTo(0.75)
            assertThat(wanted.factor).isEqualTo(1.5f)
        }

        @Test
        fun `true is true, and so is 1`() {
            assertThat(booleanFrom("true")).isTrue()
            assertThat(booleanFrom("TRUE")).isTrue()
            assertThat(booleanFrom("1")).isTrue()
        }

        @Test
        fun `anything else is false, including the empty string`() {
            assertThat(booleanFrom("false")).isFalse()
            assertThat(booleanFrom("")).isFalse()
            assertThat(booleanFrom("yes")).isFalse()
        }

        @Test
        fun `a number that is not a number fails where it is read`() {
            config.addMemoryCollection("app.port" to "not a port")

            assertThatThrownBy { provider.create<WantsAPort>() }
                .isInstanceOf(NumberFormatException::class.java)
        }

        @Test
        fun `a type nobody knows how to build says which one it was`() {
            config.addMemoryCollection("app.when" to "2026-09-20")

            assertThatThrownBy { provider.create<WantsADate>() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("2026-09-20")
                .hasMessageContaining("java.time.LocalDate")
        }
    }

    @Nested
    inner class `when the configuration says nothing` {
        @Test
        fun `a parameter with a default keeps it`() {
            assertThat(provider.create<WantsAnOptionalName>().name).isEqualTo("unnamed")
        }

        @Test
        fun `a parameter without one says which path is missing`() {
            assertThatThrownBy { provider.create<WantsAName>() }
                .isInstanceOf(RequiredConfigError::class.java)
                .hasMessageContaining("app.name")
        }
    }

    private fun booleanFrom(value: String): Boolean {
        val config = ConfigManager().apply { addMemoryCollection("app.enabled" to value) }

        return DefaultServiceProvider(ServiceRegistry(config)).create<WantsABoolean>().enabled
    }

    class WantsAName(@param:ConfigValue("app.name") val name: String)

    class WantsAnOptionalName(@param:ConfigValue("app.name") val name: String = "unnamed")

    class WantsAPort(@param:ConfigValue("app.port") val port: Int)

    class WantsABoolean(@param:ConfigValue("app.enabled") val enabled: Boolean)

    class WantsADate(@param:ConfigValue("app.when") val date: LocalDate)

    class WantsNumbers(
        @param:ConfigValue("app.port") val port: Int,
        @param:ConfigValue("app.maxSize") val maxSize: Long,
        @param:ConfigValue("app.ratio") val ratio: Double,
        @param:ConfigValue("app.factor") val factor: Float,
    )

    private val config = ConfigManager()
    private val registry = ServiceRegistry(config)
    private val provider = DefaultServiceProvider(registry)
}
