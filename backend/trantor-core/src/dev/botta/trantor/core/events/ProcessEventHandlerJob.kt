package dev.botta.trantor.core.events

import dev.botta.trantor.core.jobs.*

data class ProcessEventHandlerJob(
    val handlerType: String,
    val eventType: String,
    val eventBody: String,
): Job() {
    internal class Handler(
        private val process: (job: ProcessEventHandlerJob) -> Unit,
    ): JobHandler<ProcessEventHandlerJob> {
        override fun execute(job: ProcessEventHandlerJob) {
            process(job)
        }
    }
}
