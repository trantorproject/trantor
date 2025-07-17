package dev.botta.trantor.console

import dev.botta.lang.DetailsExt
import dev.botta.trantor.serviceProvider.*

class ConsoleApp(services: ServiceProvider): BaseConsoleApp(services) {
    companion object {
        fun build(appName: String? = null, environmentName: String? = null, details: DetailsExt<Builder> = {}) =
            Builder(appName, environmentName).apply(details).build()
    }

    class Builder(appName: String? = null, environmentName: String? = null)
        : BaseConsoleAppBuilder<ConsoleApp>(appName, environmentName) {

        override fun build() = ConsoleApp(DefaultServiceProvider(services))
    }
}
