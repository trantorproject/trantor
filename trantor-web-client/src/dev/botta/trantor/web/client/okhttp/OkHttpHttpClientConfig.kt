package dev.botta.trantor.web.client.okhttp

data class OkHttpHttpClientConfig(
    // Specifies whether to follow redirects automatically.
    var followRedirects: Boolean = false,
    // Specifies a maximum time (in milliseconds) a connection can be idle (that is, without traffic of bytes in either direction).
    var idleTimeout: Int = 10_000,
    // Specifies a time period (in milliseconds) a connection can take to connect to destinations. Zero value means infinite timeout.
    var connectTimeout: Int = 10_000,
    // Specifies a time period (in milliseconds) required to process an HTTP call: from sending a request to receiving a response. 0 to disable.
    var requestTimeout: Int = 60_000,
    // Specifies a maximum time (in milliseconds) an unused connection is kept in the pool to be reused.
    var keepAliveTimeout: Int = 300_000,
    var maxConnectionsPerDestination: Int = 1200,
)
