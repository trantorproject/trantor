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

    /** Runs while the call waits for the headers, which is all of it when the server answers at once. */
    var whileOpening: () -> Unit = {}

    var method: HttpMethods? = null

    /** The method of every request, in the order of [requests]. */
    val methods = mutableListOf<HttpMethods>()
    var request: HttpRequest? = null
    var options: StreamOptions? = null
    var wasCancelled = false
    var wasClosed = false

    /** Every request, in order: a tool loop makes one per step. */
    val requests = mutableListOf<HttpRequest>()
    private val queued = ArrayDeque<Answer>()

    val requestBody get() = request?.body as String?

    /** The bodies of the next calls, one each and in order. Once they run out, [body] answers. */
    fun answers(vararg bodies: String) = apply { queued.addAll(bodies.map { Answer(it) }) }

    /** The next call answers with its own status, content type and headers, like a server that streams some. */
    fun answer(
        body: String,
        status: Int? = null,
        contentType: String = "application/json",
        headers: Map<String, String>? = null,
    ) = apply { queued.add(Answer(body, status, contentType, headers)) }

    override fun stream(method: HttpMethods, request: HttpRequest, options: StreamOptions): HttpStreamResponse {
        this.method = method
        this.request = request
        this.options = options
        requests.add(request)
        methods.add(method)

        error?.let { throw it }

        // As the client of Trantor: it listens to the cancellation from before it opens the call until it is closed
        val cancellation = options.cancellation?.onCancel { wasCancelled = true }
        whileOpening()
        if (options.cancellation?.isCancelled == true) {
            cancellation?.close()
            throw HttpClientError("Canceled")
        }

        return FakeStreamResponse(nextAnswer(), cancellation)
    }

    override fun delete(request: HttpRequest): HttpResponse {
        method = HttpMethods.Delete
        this.request = request
        requests.add(request)
        methods.add(HttpMethods.Delete)

        val answer = nextAnswer()
        val bytes = answer.body.toByteArray()
        return HttpResponse(answer.status ?: status, bytes, answer.contentType, headers = headersOf(answer))
    }

    private fun nextAnswer() = queued.removeFirstOrNull() ?: Answer(body)

    private fun headersOf(answer: Answer) = answer.headers ?: responseHeaders

    override fun get(request: HttpRequest) = notUsed()

    override fun post(request: HttpRequest) = notUsed()

    override fun put(request: HttpRequest) = notUsed()

    override fun patch(request: HttpRequest) = notUsed()

    private fun notUsed(): HttpResponse = throw UnsupportedOperationException("Not used by these tests")

    private class Answer(
        val body: String,
        val status: Int? = null,
        val contentType: String = "application/json",
        val headers: Map<String, String>? = null,
    )

    private inner class FakeStreamResponse(
        private val answer: Answer,
        private val cancellation: AutoCloseable?,
    ): HttpStreamResponse {
        override val status = answer.status ?: this@FakeHttpClient.status
        override val contentType = answer.contentType
        override val headers = headersOf(answer)

        override fun lines(): Sequence<String> {
            whileReading()

            return answer.body.lineSequence()
        }

        override fun body(): String {
            whileReading()

            return answer.body
        }

        override fun cancel() {
            wasCancelled = true
        }

        override fun close() {
            cancellation?.close()
            wasClosed = true
        }
    }
}
