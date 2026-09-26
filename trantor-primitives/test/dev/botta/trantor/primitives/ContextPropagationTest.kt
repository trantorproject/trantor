@file:Suppress("ClassName")

package dev.botta.trantor.primitives

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Scope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class ContextPropagationTest {
    @Nested
    inner class `the block` {
        @Test
        fun `runs inside the span that was current when the context was captured`() {
            enter(request)
            val captured = ContextPropagation.capture()

            val seen = onAnotherThread { ContextPropagation.runWithContext(captured) { Span.current() } }

            assertThat(seen.spanContext).isEqualTo(request.spanContext)
        }

        @Test
        fun `sees the logging context that was captured`() {
            MDC.put("cid", "abc123")
            val captured = ContextPropagation.capture()

            val seen = onAnotherThread { ContextPropagation.runWithContext(captured) { MDC.get("cid") } }

            assertThat(seen).isEqualTo("abc123")
        }

        @Test
        fun `returns what it returns`() {
            val result = ContextPropagation.runWithContext(ContextPropagation.capture()) { 42 }

            assertThat(result).isEqualTo(42)
        }
    }

    @Nested
    inner class `what was captured` {
        @Test
        fun `does not change when the thread moves to another span`() {
            enter(request)
            val captured = ContextPropagation.capture()

            enter(job)

            assertThat(ContextPropagation.runWithContext(captured) { Span.current() }.spanContext)
                .isEqualTo(request.spanContext)
        }
    }

    @Nested
    inner class `the thread` {
        @Test
        fun `gets its own span and logging context back`() {
            enter(request)
            MDC.put("cid", "original")
            val captured = onAnotherThread {
                MDC.put("cid", "other")
                job.makeCurrent().use { ContextPropagation.capture() }
            }

            ContextPropagation.runWithContext(captured) { }

            assertThat(Span.current().spanContext).isEqualTo(request.spanContext)
            assertThat(MDC.get("cid")).isEqualTo("original")
        }

        @Test
        fun `gets them back even when the block fails`() {
            enter(request)
            MDC.put("cid", "original")
            val captured = onAnotherThread { job.makeCurrent().use { ContextPropagation.capture() } }

            assertThatThrownBy { ContextPropagation.runWithContext(captured) { error("boom") } }.hasMessage("boom")

            assertThat(Span.current().spanContext).isEqualTo(request.spanContext)
            assertThat(MDC.get("cid")).isEqualTo("original")
        }

        @Test
        fun `that started without a span ends without one`() {
            val captured = job.makeCurrent().use { ContextPropagation.capture() }

            ContextPropagation.runWithContext(captured) { }

            assertThat(Span.current().spanContext.isValid).isFalse()
        }
    }

    @AfterEach
    fun leaveTheThreadAsItWasFound() {
        scopes.reversed().forEach { it.close() }
        MDC.clear()
    }

    /** Makes [span] current until the end of the test. */
    private fun enter(span: Span) {
        scopes.add(span.makeCurrent())
    }

    private fun <T> onAnotherThread(block: () -> T): T {
        var result: Result<T>? = null
        Thread.ofVirtual().start { result = runCatching(block) }.join()
        return result!!.getOrThrow()
    }

    private val scopes = mutableListOf<Scope>()
    private val request = span("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7")
    private val job = span("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331")

    private fun span(traceId: String, spanId: String) =
        Span.wrap(SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault()))
}
