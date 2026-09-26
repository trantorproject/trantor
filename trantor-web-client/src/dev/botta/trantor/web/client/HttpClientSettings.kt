package dev.botta.trantor.web.client

import dev.botta.trantor.web.client.okhttp.OkHttpHttpClientConfig

/**
 * Read from the `httpClient` section, or from `httpClient.<key>` for a client with a name, and changed from
 * `addHttpClient { settings, services -> ... }`. Times are in milliseconds.
 */
data class HttpClientSettings(
    var followRedirects: Boolean = false,
    /** The longest a connection can go without a byte in either direction. */
    var idleTimeout: Int = 10_000,
    /** The longest a connection can take to open. 0 waits forever. */
    var connectTimeout: Int = 10_000,
    /** The longest a call can take, from sending the request to reading the whole response. 0 has no limit. */
    var requestTimeout: Int = 60_000,
    /** How long a connection nobody is using stays open, to be used again. */
    var keepAliveTimeout: Int = 300_000,
    var maxConnectionsPerDestination: Int = 1200,
) {
    internal fun toOkHttpConfig() = OkHttpHttpClientConfig(
        followRedirects = followRedirects,
        idleTimeout = idleTimeout,
        connectTimeout = connectTimeout,
        requestTimeout = requestTimeout,
        keepAliveTimeout = keepAliveTimeout,
        maxConnectionsPerDestination = maxConnectionsPerDestination,
    )
}
