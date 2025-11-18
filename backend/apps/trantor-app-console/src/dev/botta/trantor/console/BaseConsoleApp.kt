package dev.botta.trantor.console

import dev.botta.trantor.serviceProvider.ServiceProvider

abstract class BaseConsoleApp(val services: ServiceProvider) {
    val config = services.config
    val environment = services.get<AppEnvironment>()
}
