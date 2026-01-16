package dev.botta.trantor.appServices

import dev.botta.trantor.serviceProvider.ServiceProvider

abstract class AbstractApp(val services: ServiceProvider) {
    val config = services.config
    val environment = services.get<AppEnvironment>()
}
