package dev.botta.trantor.web.server

import dev.botta.trantor.hosting.HostedService
import dev.botta.trantor.primitives.CorrelationIdGenerator
import dev.botta.trantor.primitives.logging.getLogger
import dev.botta.trantor.web.server.controllers.Controller
import dev.botta.trantor.web.server.logs.HttpRequestLogger
import dev.botta.trantor.web.server.logs.LOGGABLE_URL
import dev.botta.trantor.web.server.stats.*
import dev.botta.trantor.web.server.tracing.*
import io.javalin.Javalin
import io.javalin.config.*
import io.javalin.http.Context
import io.opentelemetry.api.OpenTelemetry
import jakarta.servlet.DispatcherType
import org.apache.logging.log4j.core.config.Configurator
import org.eclipse.jetty.server.*
import org.eclipse.jetty.server.handler.StatisticsHandler
import org.eclipse.jetty.servlet.FilterHolder
import org.eclipse.jetty.util.thread.QueuedThreadPool
import org.slf4j.MDC
import java.time.Duration
import java.util.*

/**
 * The HTTP server of the application, on Javalin and Jetty, started and stopped with the host.
 *
 * Every request carries a correlation id in `X-Request-Id`, the one the client sent or a new one, which is also the
 * `cid` of its logs. With an [openTelemetry] that exports, every request is also a `SERVER` span that goes on from
 * the `traceparent` of the caller; the no-op one, the default, costs nothing.
 */
class HttpServer(
    private val settings: HttpServerSettings,
    private val openTelemetry: OpenTelemetry = OpenTelemetry.noop(),
): RouteRegistrant, HostedService {
    private val logger = getLogger()
    private val javalin: Javalin
    private val routeRegister: RouteRegister
    private val threadPool = QueuedThreadPool(settings.maxThreads, settings.minThreads, settings.idleTimeout)
    private var managementThreadPool: QueuedThreadPool? = null
    private val statisticsHandler = StatisticsHandler()
    private val requestLogger: HttpRequestLogger = settings.requestLoggerFactory(logger)
    private val secrets = SecretParams(settings.secretParams)
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
            settings.configureJavalin(javalinConfig)
            configureJetty(javalinConfig.jetty)
            javalinConfig.jetty.modifyServletContextHandler {
                val filter = FilterHolder(ServerSpanFilter(openTelemetry, secrets))
                it.addFilter(filter, "/*", EnumSet.of(DispatcherType.REQUEST))
            }
        }
        routeRegister = JavalinRouteRegister(javalin, openTelemetry, secrets)
        setupMdc()
    }

    private fun setupMdc() {
        javalin.before { ctx ->
            val correlationId =
                ctx.header("X-Request-Id")
                    ?.takeIf { it.isNotBlank() }
                    ?: generateCorrelationId()
            ctx.header("X-Request-Id", correlationId)

            MDC.put("cid", correlationId)
            MDC.put("src", "http")
        }
    }

    private fun generateCorrelationId(): String = CorrelationIdGenerator.new()

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
        // The end of the request for Javalin, sync or async, and the first moment the route is known for sure
        ServerSpans.routed(ctx)
        val base = ctx.req().requestURL.toString().removeSuffix(ctx.req().requestURI)
        ctx.attribute(LOGGABLE_URL, secrets.url(base, ctx.req().requestURI, ctx.queryString()))
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
            ServerSpans.handled(ctx, error)
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
