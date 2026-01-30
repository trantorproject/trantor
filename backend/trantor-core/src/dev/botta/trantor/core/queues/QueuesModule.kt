package dev.botta.trantor.core.queues

import dev.botta.trantor.config.*
import dev.botta.trantor.di.*
import dev.botta.trantor.hosting.*

class QueuesModule: Module {
    override fun compose(services: ServiceRegistry, config: ConfigManager) {
        services.addSingleton<QueueManager> { it.create<QueueManager>() }
        services.addHostedService { it.get<QueueManager>() }
        services.addSingleton<MessageQueue> { it.get<QueueManager>().getQueue() }
    }

    override fun initialize(services: ServiceProvider, config: Config) {
    }
}
