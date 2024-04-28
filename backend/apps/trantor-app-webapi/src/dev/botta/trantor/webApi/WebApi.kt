package dev.botta.trantor.webApi

import dev.botta.trantor.config.Config
import dev.botta.trantor.serviceProvider.ServiceRegistry

class WebApi(config: Config, registry: ServiceRegistry): BaseWebApi(config, registry) {
    companion object {
        fun createBuilder(appName: String? = null) = WebApiBuilder(appName)
    }
}
