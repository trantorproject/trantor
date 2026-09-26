package dev.botta.trantor.web.client.okhttp

import dev.botta.trantor.primitives.logging.getLogger
import dev.botta.trantor.web.client.*
import dev.botta.trantor.web.client.MultipartBody
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.source
import java.util.concurrent.TimeUnit
import okhttp3.MultipartBody as OkMultipartBody

class OkHttpHttpClient(
    val config: OkHttpHttpClientConfig = OkHttpHttpClientConfig()
): HttpClient() {
    private val logger = getLogger()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(config.followRedirects)
        .connectTimeout(config.connectTimeout.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(config.idleTimeout.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(config.idleTimeout.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout(config.requestTimeout.toLong(), TimeUnit.MILLISECONDS)
        .connectionPool(
            ConnectionPool(
                config.maxConnectionsPerDestination,
                config.keepAliveTimeout.toLong(),
                TimeUnit.MILLISECONDS
            )
        )
        .build()

    override fun get(request: HttpRequest) = sendRequest("GET", request)

    override fun post(request: HttpRequest) = sendRequest("POST", request)

    override fun put(request: HttpRequest) = sendRequest("PUT", request)

    override fun patch(request: HttpRequest) = sendRequest("PATCH", request)

    override fun delete(request: HttpRequest) = sendRequest("DELETE", request)

    override fun stream(method: HttpMethods, request: HttpRequest, options: StreamOptions): HttpStreamResponse {
        val okRequest = buildRequest(method.value, request)
        val call = streamClient(options).newCall(okRequest)

        try {
            val response = call.execute()

            logger.info("${method.value} ${request.url} Response: ${response.code} (stream)")

            return OkHttpHttpStreamResponse(call, response)
        } catch (e: Throwable) {
            logger.error("${method.value} ${request.url}", e)
            call.cancel()
            throw HttpClientError(e.message, e)
        }
    }

    // A stream is not bounded by the request timeout: it ends when the server goes silent for longer than the read timeout
    private fun streamClient(options: StreamOptions) = client.newBuilder()
        .readTimeout((options.readTimeout ?: config.idleTimeout).toLong(), TimeUnit.MILLISECONDS)
        .callTimeout((options.totalTimeout ?: 0).toLong(), TimeUnit.MILLISECONDS)
        .build()

    private fun sendRequest(method: String, request: HttpRequest): HttpResponse {
        val startTime = System.nanoTime()

        val okRequest = buildRequest(method, request)

        try {
            client.newCall(okRequest).execute().use { response ->
                val elapsedMs = (System.nanoTime() - startTime) / 1_000_000f

                val bodyBytes = response.body.bytes()
                val contentType = response.body.contentType()?.toString()
                val encoding = response.body.contentType()?.charset(Charsets.UTF_8)?.name()
                val headers = response.headers.toMap()

                logRequest(
                    method,
                    request.url,
                    response.code,
                    elapsedMs,
                    request.body,
                    if (!response.isSuccessful) bodyBytes.toString(Charsets.UTF_8) else null
                )

                return HttpResponse(
                    status = response.code,
                    bodyBytes = bodyBytes,
                    contentType = contentType,
                    encoding = encoding,
                    headers = headers
                )
            }
        } catch (e: Throwable) {
            logger.error("$method ${request.url}", e)
            throw HttpClientError(e.message, e)
        }
    }

    private fun buildRequest(method: String, request: HttpRequest): Request {
        val builder = Request.Builder()
            .url(request.url)

        request.headers.forEach { (k, v) ->
            builder.addHeader(k, v)
        }

        val body = when (request.body) {
            null -> null
            is String -> {
                val ct = request.headers["Content-Type"] ?: "application/json"
                request.body
                    .toString()
                    .toRequestBody(ct.toMediaTypeOrNull())
            }
            is MultipartBody -> (request.body as MultipartBody).toOkHttpMultipart()
            else -> throw UnsupportedOperationException("Invalid body type: ${request.body!!::class}")
        }

        builder.method(method, body)
        return builder.build()
    }

    private fun MultipartBody.toOkHttpMultipart(): RequestBody {
        val builder = OkMultipartBody.Builder()
            .setType(OkMultipartBody.FORM)

        parts.forEach { part ->
            when (part) {
                is MultipartBody.FieldPart -> {
                    builder.addFormDataPart(
                        part.name,
                        part.value ?: ""
                    )
                }
                is MultipartBody.FilePart -> {
                    val fileBody = object : RequestBody() {
                        override fun contentType(): MediaType? =
                            part.mimeType.toMediaTypeOrNull()

                        override fun writeTo(sink: okio.BufferedSink) {
                            part.data.use { input ->
                                sink.writeAll(input.source())
                            }
                        }
                    }

                    builder.addFormDataPart(part.name, part.fileName, fileBody)
                }
            }
        }

        return builder.build()
    }

    private fun logRequest(
        method: String,
        url: String,
        status: Int,
        timeMs: Float,
        requestBody: Any?,
        responseBody: String?
    ) {
        val sb = StringBuilder()
        sb.append(method)
        sb.append(" ")
        sb.append(url)
        sb.append(" Response: ")
        sb.append(status)
        sb.append(" (")
        sb.append(timeMs)
        sb.append("ms)")

        if (status >= 400) {
            sb.appendLine()
            sb.append("Request Body: $requestBody")
            sb.appendLine()
            sb.append("Response Body: $responseBody")
            logger.error(sb.toString())
        } else {
            logger.info(sb.toString())
        }
    }
}
