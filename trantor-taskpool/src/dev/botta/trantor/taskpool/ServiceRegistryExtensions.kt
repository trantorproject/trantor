package dev.botta.trantor.taskpool

import dev.botta.lang.DetailsExt
import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.hosting.addHostedService

fun ServiceRegistry.addTaskPool(details: DetailsExt<TaskPoolSettings> = {}) = apply {
    if (has<TaskPool>()) return@apply
    addSingleton { TaskPool(TaskPoolSettings().apply(details)) }
    addHostedService { it.get<TaskPool>() }
}
