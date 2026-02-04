package dev.botta.trantor.core.application

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.lang.DetailsExt
import dev.botta.trantor.config.Config
import dev.botta.trantor.di.ServiceProvider
import dev.botta.trantor.hosting.*

class Application(private val host: Host, private val executor: ApplicationExecutor): Host, ApplicationExecutor {
    override val services: ServiceProvider
        get() = host.services
    override val config: Config
        get() = host.config
    override val environment: HostEnvironment
        get() = host.environment
    override val lifetime: HostLifetime
        get() = host.lifetime

    override fun start() {
        host.start()
    }

    override fun stop(timeoutSeconds: Int) {
        host.stop(timeoutSeconds)
    }

    override fun <T: Request<R>, R> execute(request: T, context: ExecutionContext): R {
        return executor.execute(request, context)
    }

    override fun registerMiddleware(middleware: Middleware, priority: MiddlewarePriorities) {
        return executor.registerMiddleware(middleware, priority)
    }


    companion object {
        fun builder(args: Array<String>) = ApplicationBuilder(ApplicationBuilderConfig(args = args))

        fun builder(config: ApplicationBuilderConfig) = ApplicationBuilder(config)

        fun builder(details: DetailsExt<ApplicationBuilderConfig> = {}) = ApplicationBuilder(ApplicationBuilderConfig().apply(details))
    }
}
