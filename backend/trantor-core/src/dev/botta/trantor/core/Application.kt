package dev.botta.trantor.core

import dev.botta.lang.DetailsExt
import dev.botta.trantor.config.Config
import dev.botta.trantor.di.ServiceProvider
import dev.botta.trantor.hosting.*

class Application(private val host: Host): Host {
    override val services: ServiceProvider
        get() = host.services
    override val config: Config
        get() = host.config
    override val environment: HostEnvironment
        get() = host.environment

    override fun start() {
        host.start()
    }

    override fun stop(timeoutSeconds: Int) {
        host.stop(timeoutSeconds)
    }

    companion object {
        fun builder(args: Array<String>) = ApplicationBuilder(ApplicationBuilderConfig(args = args))

        fun builder(config: ApplicationBuilderConfig) = ApplicationBuilder(config)

        fun builder(details: DetailsExt<ApplicationBuilderConfig> = {}) = ApplicationBuilder(ApplicationBuilderConfig().apply(details))
    }
}
