package dev.botta.trantor.web.server

import dev.botta.trantor.core.getLogger
import dev.botta.trantor.core.lang.shortName
import dev.botta.trantor.web.server.controllers.Controller
import dev.botta.trantor.web.server.logs.HttpRequestLogger
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.bodylimit.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.timeout
import org.eclipse.jetty.util.thread.QueuedThreadPool
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

class HttpServer(private val config: HttpServerConfig): RouteRegistrant {
    private val logger = getLogger()
    private var ktorServer: EmbeddedServer<*, *>? = null
    private val routeRegister = RouteRegister()
//    private val threadPool = QueuedThreadPool(config.maxThreads, config.minThreads, config.idleTimeout)
    private var managementThreadPool: QueuedThreadPool? = null
//    private val statisticsHandler = StatisticsHandler()
    private val requestLogger: HttpRequestLogger = config.requestLoggerFactory(logger)
    val id = UUID.randomUUID().toString()
//    val stats: HttpServerStats
//        get() = statisticsHandler.getStats(threadPool, managementThreadPool)
    override val routes get() = routeRegister
    private val statusPagesConfigurations = mutableListOf<(StatusPagesConfig.() -> Unit)>()

    init {
//        javalin = Javalin.create { javalinConfig ->
//            javalinConfig.showJavalinBanner = false
//            javalinConfig.requestLogger.http(::logRequest)
//            javalinConfig.http.maxRequestSize = config.maxRequestSizeInMb * SizeUnit.MB.multiplier
//            configureJetty(javalinConfig.jetty)
//        }
    }

//    private fun configureJetty(jettyConfig: JettyConfig) {
//        Configurator.setLevel("org.eclipse.jetty", org.apache.logging.log4j.Level.WARN)
//        jettyConfig.threadPool = threadPool
//        if (config.uploadsTempDirectory != null) {
//            jettyConfig.multipartConfig.cacheDirectory(config.uploadsTempDirectory!!)
//        }
//        jettyConfig.multipartConfig.maxFileSize(config.maxMultipartFileSizeInMb, SizeUnit.MB)
//        jettyConfig.multipartConfig.maxInMemoryFileSize(config.maxMultipartInMemoryFileSizeInMb, SizeUnit.MB)
//        jettyConfig.multipartConfig.maxTotalRequestSize(config.maxMultipartRequestSizeInMb, SizeUnit.MB)
//
//        if (config.isStatsEnabled) {
//            jettyConfig.modifyServer { it.insertHandler(statisticsHandler) }
//        }
//        if (config.managementPort > 0) {
//            jettyConfig.addConnector { server, _ ->
//                managementThreadPool = QueuedThreadPool(4, 2, config.idleTimeout)
//                val managementConnector = ServerConnector(server, managementThreadPool, null, null, 1, 1, HttpConnectionFactory())
//                managementConnector.port = config.managementPort
//                managementConnector
//            }
//        }
//
//        jettyConfig.modifyWebSocketServletFactory {
//            it.idleTimeout = Duration.ofMillis(config.wsIdleTimeout.toLong())
//        }
//    }

//    private fun logRequest(call: ApplicationCall, executionTimeMs: Float) {
//        requestLogger.handle(ctx, executionTimeMs)
//    }

    fun start() {
        logger.info("Starting with id $id")
//        logger.info("ThreadPool configured with min: ${threadPool.minThreads} max: ${threadPool.maxThreads} idleTimeout: ${threadPool.idleTimeout}ms")
        ktorServer = embeddedServer(Netty, configure = {
            connectors.add(EngineConnectorBuilder().apply {
                host = "0.0.0.0"
                port = config.port
            })
            if (config.managementPort != -1) {
                connectors.add(EngineConnectorBuilder().apply {
                    host = "0.0.0.0"
                    port = config.managementPort
                })
            }
        }) {
            install(RequestBodyLimit) {
                bodyLimit { config.maxRequestSizeInMb * 1024 * 1024 }
            }
            install(StatusPages) { statusPagesConfigurations.forEach { it(this) } }
            install(CallLogging) {
                // TODO
//                format { call -> }
            }
            install(WebSockets) {
                timeout = config.wsIdleTimeout.milliseconds
            }
            routing { routes.configure(this) }
        }
        ktorServer?.start(wait = true)
    }

    fun stop() {
        ktorServer?.stop()
    }

    fun <T: Exception> addErrorHandler(errorHandler: HttpErrorHandler<T>) {
        statusPagesConfigurations.add {
            exception(errorHandler.errorType) { call, cause ->
                errorHandler.handle(call, cause, logger)
            }
        }
    }

    fun addControllers(vararg controllers: Controller) {
        controllers.forEach { registerController(it) }
    }

    private fun registerController(controller: Controller) {
        controller.registerRoutes(routeRegister)
        logger.info(controller.javaClass.shortName() + " registered")
        controller.getChildControllers().forEach { registerController(it) }
    }
}
