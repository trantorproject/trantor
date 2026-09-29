package dev.botta.trantor.web.server

import io.javalin.http.Handler
import io.javalin.websocket.WsConfig
import io.opentelemetry.api.OpenTelemetry
import java.util.function.Consumer

interface RouteRegister {
    /**
     * What the server of these routes traces with, for what builds on them and makes spans of its own, like the
     * endpoint of trantor-mcp-server: its spans then hang from the ones of the requests. The no-op one when the routes
     * are not of a server that traces.
     */
    val openTelemetry: OpenTelemetry get() = OpenTelemetry.noop()

    fun before(handler: Handler): RouteRegister

    fun beforeMatched(handler: Handler): RouteRegister

    fun after(handler: Handler): RouteRegister

    fun afterMatched(handler: Handler): RouteRegister

    fun post(path: String, handler: Handler): RouteRegister

    fun get(path: String, handler: Handler): RouteRegister

    fun put(path: String, handler: Handler): RouteRegister

    fun patch(path: String, handler: Handler): RouteRegister

    fun delete(path: String, handler: Handler): RouteRegister

    fun ws(path: String, consumer: Consumer<WsConfig>): RouteRegister

    fun wsBefore(consumer: Consumer<WsConfig>): RouteRegister

    fun wsAfter(consumer: Consumer<WsConfig>): RouteRegister
}
