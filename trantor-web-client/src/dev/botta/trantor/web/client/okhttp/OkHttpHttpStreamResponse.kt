package dev.botta.trantor.web.client.okhttp

import dev.botta.trantor.web.client.HttpClientError
import dev.botta.trantor.web.client.HttpStreamResponse
import okhttp3.Call
import okhttp3.Response
import java.io.IOException

/**
 * The response of a call to [OkHttpHttpClient.stream]. A call cancelled, with [cancel] or with the cancellation of
 * its options, ends its body where it was cut. Closing it stops listening to that cancellation.
 */
class OkHttpHttpStreamResponse internal constructor(
    private val call: StreamCall,
    private val response: Response,
    private val cancellation: AutoCloseable?,
): HttpStreamResponse {
    override val status = response.code
    override val contentType = response.body.contentType()?.toString()
    override val headers = response.headers.toMap()

    override fun lines(): Sequence<String> {
        val source = response.body.source()

        return generateSequence {
            try {
                source.readUtf8Line()
            } catch (e: IOException) {
                if (call.cancelled) null else throw HttpClientError(e.message, e)
            }
        }.constrainOnce()
    }

    override fun body(): String {
        try {
            return response.body.string()
        } catch (e: IOException) {
            if (call.cancelled) return ""
            throw HttpClientError(e.message, e)
        }
    }

    override fun cancel() {
        call.cancel()
    }

    override fun close() {
        cancellation?.close()
        response.close()
    }
}

/**
 * A call that remembers whether it was cancelled on purpose. OkHttp cancels it too when its total timeout runs out, so
 * [Call.isCanceled] cannot tell a cut stream, which ends, from one that took too long, which fails.
 */
internal class StreamCall(private val call: Call) {
    @Volatile
    var cancelled = false
        private set

    fun execute(): Response = call.execute()

    fun cancel() {
        cancelled = true
        call.cancel()
    }
}
