@file:Suppress("ClassName")

package dev.botta.trantor.hosting.defaults

import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.config.providers.addMemoryCollection
import dev.botta.trantor.hosting.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DefaultHostBuilderTest {
    @Nested
    inner class `the environment` {
        @Test
        fun `is what the builder was told`() {
            val builder = builderOf(HostBuilderConfig(environmentName = "staging", appName = "billing"))

            assertThat(builder.environment.environmentName).isEqualTo("STAGING")
            assertThat(builder.environment.appName).isEqualTo("billing")
        }

        @Test
        fun `falls back to production, the safe assumption`() {
            assertThat(builderOf().environment.isProduction).isTrue()
        }

        @Test
        fun `can come from configuration instead of from code`() {
            val config = ConfigManager().apply { addMemoryCollection("env" to "development", "appName" to "billing") }

            val builder = builderOf(HostBuilderConfig(config = config))

            assertThat(builder.environment.isDevelopment).isTrue()
            assertThat(builder.environment.appName).isEqualTo("billing")
        }

        @Test
        fun `what the builder was told wins over what configuration said`() {
            val config = ConfigManager().apply { addMemoryCollection("env" to "development") }

            val builder = builderOf(HostBuilderConfig(environmentName = "staging", config = config))

            assertThat(builder.environment.isStaging).isTrue()
        }

        @Test
        fun `is in the container, so anything can ask where it is running`() {
            val builder = builderOf(HostBuilderConfig(environmentName = "staging"))

            assertThat(builder.services.has<HostEnvironment>()).isTrue()
        }
    }

    @Nested
    inner class `the configuration` {
        @Test
        fun `is the one it was given, so an application can prepare it`() {
            val config = ConfigManager().apply { addMemoryCollection("my.setting" to "value") }

            assertThat(builderOf(HostBuilderConfig(config = config)).config["my.setting"]).isEqualTo("value")
        }

        @Test
        fun `is one of its own when nobody gave it any`() {
            assertThat(builderOf().config).isNotNull()
        }
    }

    @Nested
    inner class `building` {
        @Test
        fun `gives a host wired to what was registered`() {
            val builder = builderOf(HostBuilderConfig(appName = "billing"))

            val host = builder.build()

            assertThat(host.environment.appName).isEqualTo("billing")
            assertThat(host.config).isSameAs(builder.config)
        }

        @Test
        fun `the host is in the container, for whoever needs to stop it`() {
            val builder = builderOf()

            val host = builder.build()

            assertThat(host.services.get<Host>()).isSameAs(host)
        }

        @Test
        fun `initializes the modules that were registered`() {
            val builder = builderOf()
            val module = RecordingModule()
            builder.services.addModule(module)

            builder.build()

            assertThat(module.initialized).isTrue()
        }

        @Test
        fun `leaves them alone when it was told not to`() {
            val builder = builderOf(HostBuilderConfig(initializeModules = false))
            val module = RecordingModule()
            builder.services.addModule(module)

            builder.build()

            assertThat(module.initialized).isFalse()
        }

        @Test
        fun `a module registered with a factory is refused, because it would be composed twice`() {
            val builder = builderOf()
            builder.services.addSingleton<Module> { RecordingModule() }

            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Modules cannot be registered")
        }
    }

    /** No settings files, no environment variables: only what the test puts in. */
    private fun builderOf(config: HostBuilderConfig = HostBuilderConfig()) =
        DefaultHostBuilder(config.apply { disableDefaults = true }).also {
            it.services.addSingletonIfMissing<HostLifetime> { _ -> DefaultHostLifetime() }
        }

    private class RecordingModule: Module {
        var initialized = false

        override fun compose(services: dev.botta.trantor.di.ServiceRegistry, config: ConfigManager) {}

        override fun initialize(services: dev.botta.trantor.di.ServiceProvider, config: dev.botta.trantor.config.Config) {
            initialized = true
        }
    }
}
