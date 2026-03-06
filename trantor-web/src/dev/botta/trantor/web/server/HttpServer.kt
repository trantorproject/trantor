package dev.botta.trantor.web.server

import dev.botta.trantor.hosting.HostedService
import dev.botta.trantor.primitives.logging.getLogger
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

class HttpServer(private val settings: HttpServerSettings): RouteRegistrant, HostedService {
    private val logger = getLogger()
    private val javalin: Javalin
    private val routeRegister: RouteRegister
    private val threadPool = QueuedThreadPool(settings.maxThreads, settings.minThreads, settings.idleTimeout)
    private var managementThreadPool: QueuedThreadPool? = null
    private val statisticsHandler = StatisticsHandler()
    private val requestLogger: HttpRequestLogger = settings.requestLoggerFactory(logger)
    val id = UUID.randomUUID().toString()
    val stats: HttpServerStats
        get() = statisticsHandler.getStats(threadPool, managementThreadPool)
    override val routes get() = routeRegister

    init {
        javalin = Javalin.create { javalinConfig ->
            javalinConfig.showJavalinBanner = false
            javalinConfig.requestLogger.http(::logRequest)
            javalinConfig.http.maxRequestSize = settings.maxRequestSizeInMb * SizeUnit.MB.multiplier
            javalinConfig.useVirtualThreads = true
            javalinConfig.startupWatcherEnabled = false
            configureJetty(javalinConfig.jetty)
        }
        routeRegister = JavalinRouteRegister(javalin)
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
        if (settings.uploadsTempDirectory != null) {
            jettyConfig.multipartConfig.cacheDirectory(settings.uploadsTempDirectory!!)
        }
        jettyConfig.multipartConfig.maxFileSize(settings.maxMultipartFileSizeInMb, SizeUnit.MB)
        jettyConfig.multipartConfig.maxInMemoryFileSize(settings.maxMultipartInMemoryFileSizeInMb, SizeUnit.MB)
        jettyConfig.multipartConfig.maxTotalRequestSize(settings.maxMultipartRequestSizeInMb, SizeUnit.MB)

        if (settings.isMetricsEnabled) {
            jettyConfig.modifyServer { it.insertHandler(statisticsHandler) }
        }
        if (settings.managementPort > 0) {
            jettyConfig.addConnector { server, _ ->
                managementThreadPool = QueuedThreadPool(4, 2, settings.idleTimeout)
                val managementConnector = ServerConnector(server, managementThreadPool, null, null, 1, 1, HttpConnectionFactory())
                managementConnector.port = settings.managementPort
                managementConnector
            }
        }

        jettyConfig.modifyWebSocketServletFactory {
            it.idleTimeout = Duration.ofMillis(settings.wsIdleTimeout.toLong())
        }
    }

    private fun logRequest(ctx: Context, executionTimeMs: Float) {
        requestLogger.handle(ctx, executionTimeMs)
        MDC.clear()
    }

    override fun start() {
        logger.info("Starting with id $id")
        logger.info("ThreadPool configured with min: ${threadPool.minThreads} max: ${threadPool.maxThreads} idleTimeout: ${threadPool.idleTimeout}ms")
        javalin.start(settings.port)
    }

    override fun stop(timeoutSeconds: Int) {
        javalin.jettyServer().server().stopTimeout = timeoutSeconds * 1_000L
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

    fun addController(controller: Controller) {
        controller.registerRoutes(routeRegister)
    }
}
