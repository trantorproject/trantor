package dev.botta.trantor.web.broadcast

import dev.botta.trantor.core.broadcast.Broadcaster
import dev.botta.trantor.di.ServiceRegistry
import dev.botta.trantor.hosting.addHostedService

fun ServiceRegistry.addBroadcaster(wsPath: String = "/broadcaster") {
    addSingleton<Broadcaster, DefaultBroadcaster>()
    addHostedService { it.get<Broadcaster>() as DefaultBroadcaster }
}
