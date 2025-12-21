package dev.botta.trantor.web.client.ktor

import dev.botta.trantor.core.getLogger
import dev.botta.trantor.web.client.*
import dev.botta.trantor.web.client.HttpRequest
import dev.botta.trantor.web.client.HttpResponse
import io.ktor.client.*
import io.ktor.client.engine.apache5.*
import io.ktor.client.network.sockets.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.streams.*
import kotlinx.io.buffered
import java.util.concurrent.TimeoutException
import kotlin.math.roundToInt
import dev.botta.trantor.web.client.HttpClient as TrantorHttpClient

class KtorHttpClient(config: KtorHttpClientConfig = KtorHttpClientConfig()): TrantorHttpClient() {
    private val logger = getLogger()
    private val client = HttpClient(Apache5) {
        expectSuccess = true
        engine {
            followRedirects = config.followRedirects
            socketTimeout = config.socketTimeout
            connectTimeout = config.connectTimeout.toLong()
            connectionRequestTimeout = config.connectionRequestTimeout.toLong()
        }
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeout.toLong()
        }
    }

    override suspend fun get(request: HttpRequest): HttpResponse {
        return sendRequest(HttpMethod.Get, request)
    }

    override suspend fun post(request: HttpRequest): HttpResponse {
        return sendRequest(HttpMethod.Post, request)
    }

    override suspend fun put(request: HttpRequest): HttpResponse {
        return sendRequest(HttpMethod.Put, request)
    }

    override suspend fun patch(request: HttpRequest): HttpResponse {
        return sendRequest(HttpMethod.Patch, request)
    }

    override suspend fun delete(request: HttpRequest): HttpResponse {
        return sendRequest(HttpMethod.Delete, request)
    }

    private suspend fun sendRequest(method: HttpMethod, request: HttpRequest): HttpResponse {
        try {
            val startTime = System.nanoTime()
            val response = client.request(request.url) {
                this.method = method

                if (method != HttpMethod.Get) {
                    when (request.body) {
                        is String? -> setBody(request.body as String? ?: "")
                        is MultipartBody -> setBody((request.body as MultipartBody).toMultiPartFormDataContent())
                        else -> throw UnsupportedOperationException("Invalid body type")
                    }
                    if (request.body !is MultipartBody) {
                        contentType(
                            ContentType.parse(
                                request.headers["Content-Type"] ?: "application/x-www-form-urlencoded"
                            )
                        )
                    }
                }
                headers {
                    request.headers.forEach { append(it.key, it.value) }
                }
            }
            logRequest(request, response, startTime)

            return HttpResponse(
                response.status.value,
                response.bodyAsBytes(),
                response.contentType()?.let { "${it.contentType}/${it.contentSubtype}" },
                (response.charset() ?: Charsets.UTF_8).name(),
                response.headers.toMap().mapValues { it.value.first() },
            )
        } catch (e: ResponseException) {
            logger.error("$method ${request.url}")
            throw HttpClientError(e.message, e)
        } catch (e: ConnectTimeoutException) {
            logger.error("$method ${request.url}")
            throw(e)
        } catch (e: SocketTimeoutException) {
            logger.error("$method ${request.url}")
            throw(e)
        } catch (e: HttpRequestTimeoutException) {
            logger.error("$method ${request.url}")
            throw(e)
        } catch (e: TimeoutException) {
            logger.error("$method ${request.url}")
            throw(e)
        }
    }

    private fun MultipartBody.toMultiPartFormDataContent(): MultiPartFormDataContent {
        val content = MultiPartFormDataContent(
            formData {
                parts.forEach {
                    when(it) {
                        is MultipartBody.FieldPart -> {
                            append(it.name, it.value ?: "", it.fields?.toHeaders() ?: Headers.Empty)
                        }
                        is MultipartBody.FilePart -> {
                            val headers = it.fields?.toMutableMap() ?: mutableMapOf()
                            headers[HttpHeaders.ContentType] = it.mimeType
                            headers[HttpHeaders.ContentDisposition] = "filename=\"${it.fileName}\""
                            append(
                                it.name,
                                InputProvider { it.data.asInput().buffered() },
                                it.fields?.toHeaders() ?: Headers.Empty,
                            )
                        }
                    }
                }
            }
        )
        return content
    }

    private fun Map<String, String>.toHeaders(): Headers {
        return HeadersImpl(this.mapValues { listOf(it.value) })
    }

    private suspend fun logRequest(request: HttpRequest, response: io.ktor.client.statement.HttpResponse, startTime: Long) {
        val executionTimeMs = ((System.nanoTime() - startTime) / 1_000_000f).roundToInt()
        val sb = StringBuilder()
        sb.append(response.request.method)
        sb.append(" " + request.url)
        sb.append(" Response: " + response.status)
        sb.append(" (" + executionTimeMs + "ms)")
        if (!response.status.isSuccess()) {
            sb.appendLine()
            sb.append("Request Body: ${request.body}")
            sb.appendLine()
            sb.append("Response Body: " + response.bodyAsText())
            logger.error(sb.toString())
            return
        }
        logger.info(sb.toString())
    }
}
