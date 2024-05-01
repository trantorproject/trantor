package dev.botta.trantor.webApi

import dev.botta.lang.DetailsExt
import dev.botta.trantor.serviceProvider.ServiceRegistry

class SimpleWebApi(registry: ServiceRegistry): BaseWebApi(registry) {
    companion object {
        fun build(appName: String? = null, details: DetailsExt<Builder> = {}) = Builder(appName).apply(details).build()
    }

    class Builder(appName: String? = null, environmentName: String? = null)
        : BaseWebApiBuilder<SimpleWebApi>(appName, environmentName) {

        override fun build() = SimpleWebApi(services)
    }
}
