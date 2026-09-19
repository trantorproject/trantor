package dev.botta.trantor.web.client.okhttp

import dev.botta.trantor.web.client.HttpClientError
import dev.botta.trantor.web.client.HttpStreamResponse
import okhttp3.Call
import okhttp3.Response
import java.io.IOException

class OkHttpHttpStreamResponse(
    private val call: Call,
    private val response: Response,
): HttpStreamResponse {
    @Volatile private var canceled = false

    override val status = response.code
    override val contentType = response.body.contentType()?.toString()
    override val headers = response.headers.toMap()

    override fun lines(): Sequence<String> {
        val source = response.body.source()

        return generateSequence {
            try {
                source.readUtf8Line()
            } catch (e: IOException) {
                if (canceled) null else throw HttpClientError(e.message, e)
            }
        }.constrainOnce()
    }

    override fun body(): String {
        try {
            return response.body.string()
        } catch (e: IOException) {
            if (canceled) return ""
            throw HttpClientError(e.message, e)
        }
    }

    override fun cancel() {
        canceled = true
        call.cancel()
    }

    override fun close() {
        response.close()
    }
}
