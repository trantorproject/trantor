package dev.botta.trantor.core.scheduling

import com.github.kagkarlsson.scheduler.task.helper.*
import dev.botta.trantor.hosting.HostedService
import dev.botta.trantor.primitives.logging.getLogger
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.serialization.gson.GsonSerializer
import org.slf4j.MDC
import javax.sql.DataSource
import com.github.kagkarlsson.scheduler.Scheduler as DbScheduler
import com.github.kagkarlsson.scheduler.serializer.GsonSerializer as SchedulerGsonSerializer

class Scheduler(private val dataSource: DataSource, private val jsonSerializer: JsonSerializer): HostedService {
    private val logger = getLogger()
    @Volatile
    private lateinit var scheduler: DbScheduler
    private val tasks = mutableListOf<RecurringTask<*>>()

    fun add(job: ScheduledJob) {
        tasks.add(Tasks.recurring(job.name, job.schedule).execute { instance, context ->
            MDC.put("src", "scheduler")
            try {
                logger.info("Executing recurring job ${job.name}")
                job.execute()
            } finally {
                MDC.remove("src")
            }
        })
    }

    override fun start() {
        val gson = (jsonSerializer as? GsonSerializer)?.getGson()
        val scheduler = DbScheduler
            .create(dataSource)
            .serializer(if (gson == null) SchedulerGsonSerializer() else SchedulerGsonSerializer(gson))
            .startTasks(tasks)
            .build()
        scheduler.start()
    }

    override fun stop(timeoutSeconds: Int) {
        scheduler.stop()
    }
}
