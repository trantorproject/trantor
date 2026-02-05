package dev.botta.trantor.core.scheduling

import com.github.kagkarlsson.scheduler.task.schedule.Schedule
import dev.botta.lang.extensions.toSnakeCase

interface ScheduledJob {
    val schedule: Schedule
    val name get() = javaClass.simpleName.toSnakeCase()

    fun execute()
}
