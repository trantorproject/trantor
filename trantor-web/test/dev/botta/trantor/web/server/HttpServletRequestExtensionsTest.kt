@file:Suppress("ClassName")

package dev.botta.trantor.web.server

import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class HttpServletRequestExtensionsTest {
    @Nested
    inner class `the address of the client` {
        @Test
        fun `of IPv6 comes without the brackets it has in a URL, which are not part of it`() {
            assertThat(requestFrom("[0:0:0:0:0:0:0:1]").clientAddress).isEqualTo("0:0:0:0:0:0:0:1")
        }

        @Test
        fun `of IPv4 comes as it is`() {
            assertThat(requestFrom("10.0.0.7").clientAddress).isEqualTo("10.0.0.7")
        }
    }

    private fun requestFrom(address: String) = mockk<HttpServletRequest> { every { remoteAddr } returns address }
}
