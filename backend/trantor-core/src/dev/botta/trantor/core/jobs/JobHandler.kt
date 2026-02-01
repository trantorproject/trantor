package dev.botta.trantor.core.jobs

interface JobHandler<T: Job> {
    fun execute(job: T)
}
