package dev.botta.trantor.ai.testing

import dev.botta.trantor.web.client.*

/** Http client that answers with a recorded response and records what it was asked. */
class FakeHttpClient(
    var status: Int = 200,
    var body: String = "{}",
    var responseHeaders: Map<String, String> = emptyMap(),
    var error: Throwable? = null,
): HttpClient() {
    /** Runs while the body is being read, to try what happens in the middle of a call. */
    var whileReading: () -> Unit = {}

    var method: HttpMethods? = null
    var request: HttpRequest? = null
    var options: StreamOptions? = null
    var wasCancelled = false
    var wasClosed = false

    val requestBody get() = request?.body as String?

    override fun stream(method: HttpMethods, request: HttpRequest, options: StreamOptions): HttpStreamResponse {
        this.method = method
        this.request = request
        this.options = options

        error?.let { throw it }

        return FakeStreamResponse()
    }

    override fun get(request: HttpRequest) = notUsed()

    override fun post(request: HttpRequest) = notUsed()

    override fun put(request: HttpRequest) = notUsed()

    override fun patch(request: HttpRequest) = notUsed()

    override fun delete(request: HttpRequest) = notUsed()

    private fun notUsed(): HttpResponse = throw UnsupportedOperationException("Not used by these tests")

    private inner class FakeStreamResponse: HttpStreamResponse {
        override val status = this@FakeHttpClient.status
        override val contentType = "application/json"
        override val headers = responseHeaders

        override fun lines() = body.lineSequence()

        override fun body(): String {
            whileReading()

            return this@FakeHttpClient.body
        }

        override fun cancel() {
            wasCancelled = true
        }

        override fun close() {
            wasClosed = true
        }
    }
}
