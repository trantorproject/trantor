package dev.botta.trantor.taskpool

import dev.botta.lang.DetailsExt
import dev.botta.trantor.di.ServiceRegistry

fun ServiceRegistry.addTaskPool(details: DetailsExt<TaskPoolSettings> = {}) = apply {
    if (has<TaskPool>()) return@apply
    addSingletonIfMissing<TaskPool> { TaskPool(TaskPoolSettings().apply(details)) }
}
