package dev.botta.trantor.web.server

import dev.botta.trantor.web.server.logs.*
import io.javalin.config.JavalinConfig
import org.slf4j.Logger

/** The settings of the [HttpServer], read from the `httpServer` section by `addHttpServer()`. */
data class HttpServerSettings(
    /**
     * 8080, and not 80, because Linux only lets a process running as root, or with `CAP_NET_BIND_SERVICE`, open a
     * port below 1024: a container that runs as an unprivileged user could not start. A proxy or a load balancer in
     * front is what serves 80 or 443.
     */
    var port: Int = 8080,
    var isMetricsEnabled: Boolean = false,
    var managementPort: Int = -1,
    var idleTimeout: Int = 30_000,
    var wsIdleTimeout: Int = 30_000,
    var maxThreads: Int = 16,
    var minThreads: Int = 4,
    var uploadsTempDirectory: String? = null,
    var maxRequestSizeInMb: Long = 1L,
    var maxMultipartFileSizeInMb: Long = 100L,
    var maxMultipartInMemoryFileSizeInMb: Int = 10,
    var maxMultipartRequestSizeInMb: Long = 500L,
    /**
     * The params whose values must not reach a trace or a log, by name: a token in the path of a route
     * (`/mcp/{token}`), of HTTP, websocket or MCP, or in the query (`?token=`). Their values are written `REDACTED`
     * in the span of the request and in the URL [requestLoggerFactory] loggers get with `loggableUrl()`.
     */
    var secretParams: Set<String> = emptySet(),
    var requestLoggerFactory: (logger: Logger) -> HttpRequestLogger = { DefaultHttpRequestLogger(it) },
    var configureJavalin: (config: JavalinConfig) -> Unit = {},
)
