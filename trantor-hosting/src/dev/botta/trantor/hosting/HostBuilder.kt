package dev.botta.trantor.hosting

import dev.botta.trantor.config.ConfigManager
import dev.botta.trantor.di.ServiceRegistry

interface HostBuilder {
    val config: ConfigManager
    val services: ServiceRegistry
    val environment: HostEnvironment
}
