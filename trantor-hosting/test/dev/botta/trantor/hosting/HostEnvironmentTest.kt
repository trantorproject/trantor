@file:Suppress("ClassName")

package dev.botta.trantor.hosting

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class HostEnvironmentTest {
    @Nested
    inner class `the environment name` {
        @Test
        fun `is kept in upper case, whatever it was written in`() {
            assertThat(HostEnvironment("development", "app").environmentName).isEqualTo("DEVELOPMENT")
            assertThat(HostEnvironment("Production", "app").environmentName).isEqualTo("PRODUCTION")
        }
    }

    @Nested
    inner class `isEnvironment` {
        @Test
        fun `is true only for the one it is in`() {
            val environment = HostEnvironment("staging", "app")

            assertThat(environment.isEnvironment("STAGING")).isTrue()
            assertThat(environment.isEnvironment("PRODUCTION")).isFalse()
        }

        @Test
        fun `does not care how the question is written`() {
            val environment = HostEnvironment("STAGING", "app")

            assertThat(environment.isEnvironment("staging")).isTrue()
            assertThat(environment.isEnvironment("Staging")).isTrue()
        }

        @Test
        fun `an environment Trantor knows nothing about still answers`() {
            val environment = HostEnvironment("qa", "app")

            assertThat(environment.isEnvironment("qa")).isTrue()
            assertThat(environment.isDevelopment).isFalse()
        }
    }

    @Nested
    inner class `the three Trantor knows` {
        @Test
        fun `development`() {
            val environment = HostEnvironment("development", "app")

            assertThat(environment.isDevelopment).isTrue()
            assertThat(environment.isStaging).isFalse()
            assertThat(environment.isProduction).isFalse()
        }

        @Test
        fun `staging`() {
            val environment = HostEnvironment("staging", "app")

            assertThat(environment.isDevelopment).isFalse()
            assertThat(environment.isStaging).isTrue()
            assertThat(environment.isProduction).isFalse()
        }

        @Test
        fun `production`() {
            val environment = HostEnvironment("production", "app")

            assertThat(environment.isDevelopment).isFalse()
            assertThat(environment.isStaging).isFalse()
            assertThat(environment.isProduction).isTrue()
        }
    }

    @Test
    fun `says the application and the environment, for a log line at startup`() {
        assertThat(HostEnvironment("production", "billing").toString()).isEqualTo("billing - PRODUCTION")
    }
}
