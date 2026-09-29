@file:Suppress("ClassName")

package dev.botta.trantor.web.application.routes

import dev.botta.trantor.web.server.RouteRegister
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.OpenTelemetry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ApplicationRouteRegisterTest {
    @Test
    fun `traces with the OpenTelemetry of the routes it registers on`() {
        val openTelemetry = mockk<OpenTelemetry>()
        val routes = mockk<RouteRegister> { every { this@mockk.openTelemetry } returns openTelemetry }

        val register = ApplicationRouteRegister(routes, mockk(), mockk())

        assertThat(register.openTelemetry).isSameAs(openTelemetry)
    }
}
