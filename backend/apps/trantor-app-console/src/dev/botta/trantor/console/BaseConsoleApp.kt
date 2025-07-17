package dev.botta.trantor.console

import dev.botta.trantor.config.config
import dev.botta.trantor.serviceProvider.*

abstract class BaseConsoleApp(val services: ServiceProvider) {
    val config = services.config
    val environment = services.get<AppEnvironment>()
}
