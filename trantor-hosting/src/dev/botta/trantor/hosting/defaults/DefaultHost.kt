package dev.botta.trantor.hosting.defaults

import dev.botta.trantor.config.Config
import dev.botta.trantor.di.ServiceProvider
import dev.botta.trantor.hosting.*
import dev.botta.trantor.primitives.logging.getLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The [Host] that [DefaultHostBuilder] builds.
 *
 * Building one registers a JVM shutdown hook, whether or not it ever starts, and the hook stays until the JVM exits.
 * It holds only the [lifetime], so a host that is discarded can be collected.
 */
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
        registerShutdownHook(lifetime)
    }

    private companion object {
        // Out of the instance on purpose. The JVM keeps a hook until it exits, so what the hook captures is never
        // collected. A lambda in an init block that reads `lifetime` looks like it captures that parameter, but the
        // compiler reads the property instead, so it captures `this` and, with it, every service of the host.
        fun registerShutdownHook(lifetime: DefaultHostLifetime) {
            Runtime.getRuntime().addShutdownHook(Thread { lifetime.stopApplication() })
        }
    }

    override fun start() {
        if (!started.compareAndSet(false, true)) return
        logger.info("Starting host [${environment.appName}] in ${environment.environmentName}")
        hostedServices = services.getAll<HostedService>()
        for (service in hostedServices) {
            logger.info("Starting service ${service.name}")
            service.start()
        }
        lifetime.notifyStarted()
    }

    override fun stop(timeoutSeconds: Int) {
        logger.info("Stopping host [${environment.appName}]")
        lifetime.notifyStopping()
        for (service in hostedServices.reversed()) {
            try {
                logger.info("Stopping service ${service.name}")
                service.stop(timeoutSeconds)
            } catch (e: Throwable) {
                logger.error("Error stopping service ${service.name}", e)
            }
        }
        lifetime.notifyStopped()
    }
}
