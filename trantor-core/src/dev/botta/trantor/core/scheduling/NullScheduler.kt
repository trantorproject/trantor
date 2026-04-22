package dev.botta.trantor.core.scheduling

class NullScheduler: Scheduler {
    override fun add(job: ScheduledJob) {
    }

    override fun start() {
    }

    override fun stop(timeoutSeconds: Int) {
    }
}
