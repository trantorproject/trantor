package dev.botta.trantor.web.server

import dev.botta.trantor.core.lang.shortName
import dev.botta.trantor.core.logging.getLogger
import dev.botta.trantor.web.server.controllers.Controller
import dev.botta.trantor.web.server.logs.HttpRequestLogger
import dev.botta.trantor.web.server.stats.*
import io.javalin.Javalin
import io.javalin.config.*
import io.javalin.http.Context
import org.apache.logging.log4j.core.config.Configurator
import org.eclipse.jetty.server.*
import org.eclipse.jetty.server.handler.StatisticsHandler
import org.eclipse.jetty.util.thread.QueuedThreadPool
import org.slf4j.MDC
import java.time.Duration
import java.util.*

class HttpServer(private val config: HttpServerConfig): RouteRegistrant {
    private val logger = getLogger()
    private val javalin: Javalin
    private val routeRegister: RouteRegister
    private val threadPool = QueuedThreadPool(config.maxThreads, config.minThreads, config.idleTimeout)
    private var managementThreadPool: QueuedThreadPool? = null
    private val statisticsHandler = StatisticsHandler()
    private val requestLogger: HttpRequestLogger = config.requestLoggerFactory(logger)
    val id = UUID.randomUUID().toString()
    val stats: HttpServerStats
        get() = statisticsHandler.getStats(threadPool, managementThreadPool)
    override val routes get() = routeRegister

    init {
        javalin = Javalin.create { javalinConfig ->
            javalinConfig.showJavalinBanner = false
            javalinConfig.requestLogger.http(::logRequest)
            javalinConfig.http.maxRequestSize = config.maxRequestSizeInMb * SizeUnit.MB.multiplier
            javalinConfig.useVirtualThreads = true
            configureJetty(javalinConfig.jetty)
        }
        routeRegister = RouteRegister(javalin)
        setupMdc()
    }

    private fun setupMdc() {
        javalin.before { ctx ->
            val callId =
                ctx.header("X-Request-Id")
                    ?.takeIf { it.isNotBlank() }
                    ?: generateCallId()
            ctx.header("X-Request-Id", callId)

            MDC.put("cid", callId)
            MDC.put("src", "http")
        }
    }

    private fun generateCallId(): String = UUID.randomUUID().toString().replace("-", "").take(10)

    private fun configureJetty(jettyConfig: JettyConfig) {
        Configurator.setLevel("org.eclipse.jetty", org.apache.logging.log4j.Level.WARN)
        jettyConfig.threadPool = threadPool
        if (config.uploadsTempDirectory != null) {
            jettyConfig.multipartConfig.cacheDirectory(config.uploadsTempDirectory!!)
        }
        jettyConfig.multipartConfig.maxFileSize(config.maxMultipartFileSizeInMb, SizeUnit.MB)
        jettyConfig.multipartConfig.maxInMemoryFileSize(config.maxMultipartInMemoryFileSizeInMb, SizeUnit.MB)
        jettyConfig.multipartConfig.maxTotalRequestSize(config.maxMultipartRequestSizeInMb, SizeUnit.MB)

        if (config.isMetricsEnabled) {
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

        jettyConfig.modifyWebSocketServletFactory {
            it.idleTimeout = Duration.ofMillis(config.wsIdleTimeout.toLong())
        }
    }

    private fun logRequest(ctx: Context, executionTimeMs: Float) {
        requestLogger.handle(ctx, executionTimeMs)
        MDC.clear()
    }

    fun start() {
        logger.info("Starting with id $id")
        logger.info("ThreadPool configured with min: ${threadPool.minThreads} max: ${threadPool.maxThreads} idleTimeout: ${threadPool.idleTimeout}ms")
        javalin.start(config.port)
    }

    fun stop() {
        javalin.stop()
    }

    fun <T: Exception> addErrorHandler(errorHandler: HttpErrorHandler<T>) {
        javalin.exception(errorHandler.errorType) { error: T, ctx: Context ->
            errorHandler.handle(error, ctx, logger)
        }
    }

    fun addInterceptor(interceptor: HttpRequestInterceptor) {
        javalin.before { interceptor.onRequest(it) }
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
