package dev.botta.trantor.webApi

import dev.botta.lang.DetailsExt
import dev.botta.trantor.serviceProvider.*

class WebApi(services: ServiceProvider): BaseWebApi(services) {
    companion object {
        fun build(appName: String? = null, environmentName: String? = null, details: DetailsExt<Builder> = {}) =
            Builder(appName, environmentName).apply(details).build()
    }

    class Builder(appName: String? = null, environmentName: String? = null)
        : BaseWebApiBuilder<WebApi>(appName, environmentName) {

        override fun build() = WebApi(DefaultServiceProvider(services))
    }
}
