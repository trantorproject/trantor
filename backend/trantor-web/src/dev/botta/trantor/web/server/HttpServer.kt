package dev.botta.trantor.web.server

import dev.botta.trantor.web.server.logs.*
import dev.botta.trantor.web.server.stats.*
import io.javalin.Javalin
import io.javalin.config.JettyConfig
import io.javalin.http.Context
import org.eclipse.jetty.server.*
import org.eclipse.jetty.server.handler.StatisticsHandler
import org.eclipse.jetty.util.thread.QueuedThreadPool
import org.slf4j.LoggerFactory
import java.util.*

class HttpServer(private val config: Config) {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val javalin: Javalin
    private val threadPool = QueuedThreadPool(config.maxThreads, config.minThreads, config.idleTimeout)
    private var managementThreadPool: QueuedThreadPool? = null
    private val statisticsHandler = StatisticsHandler()
    private val requestLogger: HttpRequestLogger = config.requestLogger ?: DefaultHttpRequestLogger(logger)
    val id = UUID.randomUUID().toString()
    val stats: HttpServerStats
        get() = statisticsHandler.getStats(threadPool, managementThreadPool)

    init {
        javalin = Javalin.create { config ->
            config.showJavalinBanner = false
            config.requestLogger.http(::logRequest)
            configureJetty(config.jetty)
        }
    }

    private fun configureJetty(jettyConfig: JettyConfig) {
        jettyConfig.threadPool = threadPool
        if (config.isStatsEnabled) {
            jettyConfig.modifyServer { it.insertHandler(statisticsHandler) }
        }
        if (config.managementPort > 0) {
            jettyConfig.addConnector { server, _ ->
                managementThreadPool = QueuedThreadPool(4, 2, config.idleTimeout)
                val managementConnector = ServerConnector(server, managementThreadPool, null, null, 1, 1, HttpConnectionFactory())
                managementConnector.port = config.managementPort
                managementConnector
            }
        }
    }

    private fun logRequest(ctx: Context, executionTimeMs: Float) {
        requestLogger.handle(ctx, executionTimeMs)
    }

    fun start() {
        logger.info("Starting HttpServer with id $id")
        javalin.start(config.port)
    }

    fun stop() {
        javalin.stop()
    }

    data class Config(
        val port: Int = 80,
        val isStatsEnabled: Boolean = false,
        val managementPort: Int = -1,
        val idleTimeout: Int = 30_000,
        val maxThreads: Int = 250,
        val minThreads: Int = 8,
        val requestLogger: HttpRequestLogger? = null,
    )
}
