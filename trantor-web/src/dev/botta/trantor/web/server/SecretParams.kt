package dev.botta.trantor.web.server

import dev.botta.trantor.primitives.telemetry.UrlRedaction
import dev.botta.trantor.primitives.telemetry.UrlRedaction.REDACTED

/**
 * The params of a request whose values must not reach a trace or a log, by name ([HttpServerSettings.secretParams]):
 * a token in the path of a route (`/mcp/{token}`), or in the query (`?token=`). A path is redacted by the routes the
 * server knows ([route]) and not by the one Javalin matched, so that it is redacted from the moment the request
 * arrives: a span has its `url.path` from its start, and a websocket leaves no match behind.
 *
 * A path is taken for a route when its fixed segments are the same and it has a segment for each `{param}`. When two
 * routes could take it, the first registered one redacts it, which at worst redacts a segment of a path that is not
 * secret: a route with a fixed segment where the other has a secret param (`/mcp/health` and `/mcp/{token}`).
 */
internal class SecretParams(private val names: Set<String>) {
    private val routes = mutableListOf<List<String>>()

    /** Knows [route], when one of its params is secret. */
    @Synchronized
    fun route(route: String) {
        val segments = segmentsOf(route)
        if (segments.any { paramOf(it) in names }) routes.add(segments)
    }

    /** [path] with the values of the secret params of the route it is of redacted. */
    fun path(path: String): String {
        if (routes.isEmpty()) return path

        val segments = segmentsOf(path)
        val route = synchronized(this) { routes.firstOrNull { matches(it, segments) } } ?: return path

        return "/" + redacted(route, segments).joinToString("/")
    }

    fun query(query: String) = UrlRedaction.query(query, names)

    /** A URL of [base], [path] and [query], each with what is secret in it redacted. */
    fun url(base: String, path: String, query: String?) = base + path(path) + (query?.let { "?" + query(it) } ?: "")

    private fun matches(route: List<String>, path: List<String>): Boolean {
        route.forEachIndexed { i, segment ->
            if (takesTheRest(segment)) return path.size > i
            if (i >= path.size) return false
            if (paramOf(segment) == null && segment != path[i]) return false
        }

        return route.size == path.size
    }

    /**
     * Each secret param in its place. A param that takes slashes, or a wildcard, takes an unknown number of segments,
     * so from the first of them on everything is redacted when a secret param is there or after it.
     */
    private fun redacted(route: List<String>, path: List<String>): List<String> {
        val rest = route.indexOfFirst { takesTheRest(it) }.takeIf { it >= 0 }
        val secretInTheRest = rest != null && route.drop(rest).any { paramOf(it) in names }

        return path.mapIndexedNotNull { i, value ->
            when {
                secretInTheRest && i > rest!! -> null
                secretInTheRest && i == rest -> REDACTED
                i < route.size && paramOf(route[i]) in names -> REDACTED
                else -> value
            }
        }
    }

    private fun segmentsOf(path: String) = path.trim('/').split('/').filter { it.isNotEmpty() }

    /** The name of the param of [segment], `{name}` or `<name>`, or null for a fixed one. */
    private fun paramOf(segment: String) = when {
        segment.startsWith("{") && segment.endsWith("}") -> segment.substring(1, segment.length - 1)
        segment.startsWith("<") && segment.endsWith(">") -> segment.substring(1, segment.length - 1)
        else -> null
    }

    private fun takesTheRest(segment: String) = segment == "*" || segment.startsWith("<")
}
