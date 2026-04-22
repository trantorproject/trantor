package dev.botta.trantor.core.scheduling

import dev.botta.trantor.hosting.HostedService

interface Scheduler: HostedService {
    fun add(job: ScheduledJob)
}
