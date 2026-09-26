package dev.botta.trantor.ai.providers

import dev.botta.trantor.web.client.HttpClient
import dev.botta.trantor.web.client.okhttp.OkHttpHttpClient

/**
 * The client of the models built by hand, without a container, shared by all of them so they don't end up with a
 * connection pool each. It keeps the defaults of any other client: how long a generation may wait is not a setting
 * of the client, every call says it.
 */
internal val defaultHttpClient: HttpClient by lazy { OkHttpHttpClient() }
