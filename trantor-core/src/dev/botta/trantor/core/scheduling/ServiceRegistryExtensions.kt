package dev.botta.trantor.core.scheduling

import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.hosting.addHostedService

fun ServiceRegistry.addScheduler() {
    addSingleton<Scheduler>()
    addHostedService { it.get<Scheduler>() }
}
