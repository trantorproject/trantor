package dev.botta.trantor.hosting.defaults

import dev.botta.trantor.config.Config
import dev.botta.trantor.di.ServiceProvider
import dev.botta.trantor.hosting.*
import dev.botta.trantor.primitives.logging.getLogger
import java.util.concurrent.atomic.AtomicBoolean

class DefaultHost(
    override val services: ServiceProvider,
    override val config: Config,
    override val environment: HostEnvironment,
    override val lifetime: DefaultHostLifetime,
): Host {
    private val logger = getLogger()
    private val started = AtomicBoolean(false)
    private var hostedServices = listOf<HostedService>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            lifetime.stopApplication()
        })
    }

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        logger.info("Starting host [${environment.appName}] in ${environment.environmentName}")
        hostedServices = services.getAll<HostedService>()
        for (service in hostedServices) {
            logger.info("Starting service ${service.javaClass.simpleName}")
            service.start()
        }
        lifetime.notifyStarted()
    }

    override fun stop(timeoutSeconds: Int) {
        logger.info("Stopping host [${environment.appName}]")
        lifetime.notifyStopping()
        for (service in hostedServices.reversed()) {
            try {
                logger.info("Stopping service ${service.javaClass.simpleName}")
                service.stop(timeoutSeconds)
            } catch (e: Exception) {
                logger.error("Error stopping service ${service.javaClass.simpleName}", e)
            }
        }
        lifetime.notifyStopped()
    }
}
