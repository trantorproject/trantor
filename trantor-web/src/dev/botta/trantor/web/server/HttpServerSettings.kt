package dev.botta.trantor.web.server

import dev.botta.trantor.web.server.logs.*
import io.javalin.config.JavalinConfig
import org.slf4j.Logger

data class HttpServerSettings(
    var port: Int = 80,
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
    var requestLoggerFactory: (logger: Logger) -> HttpRequestLogger = { DefaultHttpRequestLogger(it) },
    var configureJavalin: (config: JavalinConfig) -> Unit = {},
)
