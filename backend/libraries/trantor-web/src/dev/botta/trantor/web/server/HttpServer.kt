package dev.botta.trantor.web.server

import dev.botta.trantor.web.server.controllers.Controller
import dev.botta.trantor.web.server.logs.HttpRequestLogger
import dev.botta.trantor.web.server.stats.*
import io.javalin.Javalin
import io.javalin.config.JettyConfig
import io.javalin.http.Context
import org.eclipse.jetty.server.*
import org.eclipse.jetty.server.handler.StatisticsHandler
import org.eclipse.jetty.util.thread.QueuedThreadPool
import org.slf4j.LoggerFactory
import java.util.*

class HttpServer(private val config: HttpServerConfig): RouteRegistrant {
    private val logger = LoggerFactory.getLogger(javaClass.name)
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
        javalin = Javalin.create { config ->
            config.showJavalinBanner = false
            config.requestLogger.http(::logRequest)
            configureJetty(config.jetty)
        }
        routeRegister = RouteRegister(javalin)
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
        logger.info("Starting with id $id")
        logger.info("ThreadPool configured with min: ${threadPool.minThreads} max: ${threadPool.maxThreads} idleTimeout: ${threadPool.idleTimeout}ms")
        javalin.start(config.port)
    }

    fun stop() {
        javalin.stop()
    }

    fun <T: Exception> registerException(clazz: Class<T>, handler: HttpErrorHandler<T>) {
        javalin.exception(clazz) { error: T, ctx: Context ->
            handler.handle(error, ctx, logger)
        }
    }

    inline fun <reified T: Exception> registerException(handler: HttpErrorHandler<T>) {
        registerException(T::class.java, handler)
    }

    fun addInterceptor(interceptor: HttpRequestInterceptor) {
        javalin.before { interceptor.onRequest(it) }
    }

    fun registerControllers(vararg controllers: Controller) {
        controllers.forEach { registerController(it) }
    }

    private fun registerController(controller: Controller) {
        controller.registerRoutesIn(routeRegister)
        logger.info(controller::class.qualifiedName + " registered")
        controller.getChildControllers().forEach { registerController(it) }
    }
}

