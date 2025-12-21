package dev.botta.trantor.web.client.ktor

data class KtorHttpClientConfig(
    // Specifies whether to follow redirects automatically.
    var followRedirects: Boolean = false,
    // Specifies a maximum time (in milliseconds) of inactivity between two data packets when exchanging data with a server.
    var socketTimeout: Int = 10_000,
    // Specifies a time period (in milliseconds) in which a client should establish a connection with a server.
    var connectTimeout: Int = 10_000,
    // Specifies a time period (in milliseconds) in which a client should start a request.
    var connectionRequestTimeout: Int = 20_000,
    // Specifies a time period (in milliseconds) required to process an HTTP call: from sending a request to receiving a response. 0 to disable.
    var requestTimeout: Int = 60_000,
)
