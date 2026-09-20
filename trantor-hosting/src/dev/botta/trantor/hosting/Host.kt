package dev.botta.trantor.hosting

import dev.botta.lang.DetailsExt
import dev.botta.trantor.config.Config
import dev.botta.trantor.di.ServiceProvider
import dev.botta.trantor.hosting.defaults.DefaultHostBuilder
import java.util.concurrent.CountDownLatch

interface Host {
    val services: ServiceProvider
    val config: Config
    val environment: HostEnvironment
    val lifetime: HostLifetime

    fun start()

    fun stop(timeoutSeconds: Int = 30)

    fun run() {
        start()

        val latch = CountDownLatch(1)
        lifetime.onStopping { latch.countDown() }
        latch.await()

        stop()
    }

    fun run(runnable: () -> Unit) {
        start()
        runnable()
        stop()
    }

    companion object {
        fun builder(args: Array<String>) = DefaultHostBuilder(HostBuilderConfig(args = args))

        fun builder(config: HostBuilderConfig) = DefaultHostBuilder(config)

        fun builder(details: DetailsExt<HostBuilderConfig> = {}) =
            DefaultHostBuilder(HostBuilderConfig().apply(details))
    }
}
