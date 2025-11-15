package dev.botta.trantor.web.server

import dev.botta.trantor.web.server.logs.*

data class HttpServerConfig(
    var port: Int = 80,
    var isMetricsEnabled: Boolean = false,
    var managementPort: Int = -1,
    var idleTimeout: Int = 30_000,
    var wsIdleTimeout: Int = 30_000,
    var uploadsTempDirectory: String? = null,
    val maxRequestSizeInMb: Long = 1L,
    var maxMultipartFileSizeInMb: Long = 100L,
    var maxMultipartInMemoryFileSizeInMb: Int = 10,
    val maxMultipartRequestSizeInMb: Long = 500L,
    var requestLogger: HttpRequestLogger = DefaultHttpRequestLogger(),
)
