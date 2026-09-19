package dev.botta.trantor.web.client

/**
 * A response whose body is read while it's still being received. Must be closed.
 */
interface HttpStreamResponse: AutoCloseable {
    val status: Int
    val contentType: String?
    val headers: Map<String, String>

    /** Lines of the body, as they arrive. Can be consumed only once. */
    fun lines(): Sequence<String>

    /** Reads the remaining body at once. Useful for error responses. */
    fun body(): String

    /** Stops the stream. Safe to call from another thread. */
    fun cancel()
}
