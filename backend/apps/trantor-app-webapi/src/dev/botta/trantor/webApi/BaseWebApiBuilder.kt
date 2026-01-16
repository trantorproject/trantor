package dev.botta.trantor.webApi

import dev.botta.trantor.appServices.AbstractAppBuilder
import dev.botta.trantor.web.server.*

abstract class BaseWebApiBuilder<T: BaseWebApi>(appName: String? = null, environmentName: String? = null):
    AbstractAppBuilder<T>(appName, environmentName) {

    override fun addDefaultServices() {
        super.addDefaultServices()
        services.addConfig<HttpServerConfig>("httpServer")
        services.addSingleton { HttpServer(it.getOrDefault<HttpServerConfig> { HttpServerConfig() }) }
    }
}
