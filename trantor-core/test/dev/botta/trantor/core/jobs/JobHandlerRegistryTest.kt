@file:Suppress("ClassName")

package dev.botta.trantor.core.jobs

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JobHandlerRegistryTest {
    @Test
    fun `a handler is found by the job it handles`() {
        val handler = SendEmailHandler()
        registry.registerHandler(SendEmail::class, handler)

        assertThat(registry.getHandler(SendEmail::class)).isSameAs(handler)
    }

    @Test
    fun `each job type keeps its own handler`() {
        val sendEmail = SendEmailHandler()
        val rebuildReport = RebuildReportHandler()
        registry.registerHandler(SendEmail::class, sendEmail)
        registry.registerHandler(RebuildReport::class, rebuildReport)

        assertThat(registry.getHandler(SendEmail::class)).isSameAs(sendEmail)
        assertThat(registry.getHandler(RebuildReport::class)).isSameAs(rebuildReport)
    }

    @Test
    fun `two handlers for one job is a mistake worth stopping for`() {
        registry.registerHandler(SendEmail::class, SendEmailHandler())

        assertThatThrownBy { registry.registerHandler(SendEmail::class, SendEmailHandler()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("already registered")
            .hasMessageContaining("SendEmail")
    }

    @Test
    fun `a job nobody handles says so when it is dispatched, not silently`() {
        assertThatThrownBy { registry.getHandler(SendEmail::class) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Handler not registered")
            .hasMessageContaining("SendEmail")
    }

    private class SendEmail: Job()

    private class RebuildReport: Job()

    private class SendEmailHandler: JobHandler<SendEmail> {
        override fun execute(job: SendEmail) {}
    }

    private class RebuildReportHandler: JobHandler<RebuildReport> {
        override fun execute(job: RebuildReport) {}
    }

    private val registry = JobHandlerRegistry()
}
