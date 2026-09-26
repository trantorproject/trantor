@file:Suppress("ClassName")

package dev.botta.trantor.primitives.logging

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import org.apache.logging.log4j.core.util.ContextDataProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.*

class TraceContextDataProviderTest {
    @Nested
    inner class `inside a span` {
        @Test
        fun `every log carries its trace and span ids`() {
            val data = span.makeCurrent().use { TraceContextDataProvider().supplyContextData() }

            assertThat(data).containsExactlyInAnyOrderEntriesOf(
                mapOf("trace_id" to "4bf92f3577b34da6a3ce929d0e0e4736", "span_id" to "00f067aa0ba902b7"),
            )
        }
    }

    @Nested
    inner class `outside a span` {
        @Test
        fun `a log carries nothing`() {
            assertThat(TraceContextDataProvider().supplyContextData()).isEmpty()
        }

        @Test
        fun `nor inside the span that the API gives without an SDK`() {
            val data = Span.getInvalid().makeCurrent().use { TraceContextDataProvider().supplyContextData() }

            assertThat(data).isEmpty()
        }
    }

    @Test
    fun `Log4j finds it on its own`() {
        val providers = ServiceLoader.load(ContextDataProvider::class.java).map { it.javaClass.name }

        assertThat(providers).contains(TraceContextDataProvider::class.java.name)
    }

    private val span = Span.wrap(
        SpanContext.create(
            "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", TraceFlags.getSampled(), TraceState.getDefault(),
        ),
    )
}
