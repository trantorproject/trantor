package dev.botta.trantor.web.client.jetty

import dev.botta.trantor.core.getLogger
import dev.botta.trantor.web.client.*
import org.eclipse.jetty.client.HttpResponseException
import org.eclipse.jetty.client.api.*
import org.eclipse.jetty.client.util.StringRequestContent
import org.eclipse.jetty.http.HttpMethod
import java.util.concurrent.*
import org.eclipse.jetty.client.HttpClient as JettyHttp

class JettyHttpClient(maxConnectionsPerDestination: Int = 1200): HttpClient() {
    private val logger = getLogger()
    private val httpClient = JettyHttp()
    private var requestTimeout: Long = -1L

    init {
        httpClient.maxConnectionsPerDestination = maxConnectionsPerDestination
        httpClient.start()
    }

    fun setConnectTimeout(value: Long) {
        httpClient.connectTimeout = value
    }

    fun setIdleTimeout(value: Long) {
        httpClient.idleTimeout = value
    }

    fun setRequestTimeout(value: Long) {
        requestTimeout = value
    }

    override fun get(request: HttpRequest): HttpResponse {
        return sendRequest(HttpMethod.GET, request)
    }

    override fun post(request: HttpRequest): HttpResponse {
        return sendRequest(HttpMethod.POST, request)
    }

    override fun put(request: HttpRequest): HttpResponse {
        return sendRequest(HttpMethod.PUT, request)
    }

    override fun patch(request: HttpRequest): HttpResponse {
        return sendRequest(HttpMethod.PATCH, request)
    }

    override fun delete(request: HttpRequest): HttpResponse {
        return sendRequest(HttpMethod.DELETE, request)
    }

    private fun sendRequest(method: HttpMethod, request: HttpRequest): HttpResponse {
        val jettyRequest = createJettyRequest(request, method)
        val startTime = System.nanoTime()
        try {
            val response = jettyRequest.send()
            logRequest(jettyRequest, response, startTime, request.body)
            val headersMap = response.headers.iterator().asSequence().associate { it.name to it.value }
            return HttpResponse(response.status, response.content, response.mediaType, response.encoding, headersMap)
        } catch (e: HttpResponseException) {
            logger.error("""${jettyRequest.method} ${jettyRequest.uri}""")
            throw HttpClientError(e.message, e)
        } catch (e: TimeoutException) {
            logger.error("""${jettyRequest.method} ${jettyRequest.uri}""")
            throw e
        }
    }

//    private fun sendRequestAsync(method: HttpMethod, request: HttpRequest, listener: Response.CompleteListener) {
//        val jettyRequest = createJettyRequest(request, method)
//        val startTime = System.nanoTime()
//        val listener = Response.CompleteListener { result ->
//            if (result.isFailed) {
//                logger.error("""${jettyRequest.method} ${jettyRequest.uri}""")
////                throw HttpClientError(e.message, e)
//
//                return@CompleteListener
//            }
//            val response = result.response
//            logRequest(jettyRequest, response, startTime, request.body)
//            val headersMap = response.headers.iterator().asSequence().associate { it.name to it.value }
//            val httpResponse = HttpResponse(response.status, response.content, response.mediaType, response.encoding, headersMap)
//        }
//    }

    private fun createJettyRequest(request: HttpRequest, method: HttpMethod): Request {
        val jettyRequest = createRequest(request.url)
        if (requestTimeout != -1L) {
            jettyRequest.timeout(requestTimeout, TimeUnit.MILLISECONDS)
        }
        jettyRequest.method(method)
        if (method != HttpMethod.GET) {
            jettyRequest.body(StringRequestContent(request.body ?: ""))
            addContentType(request, jettyRequest)
        }
        addRequestHeaders(request, jettyRequest)
        return jettyRequest
    }

    private fun createRequest(url: String): Request {
        val request = httpClient.newRequest(url)
        request.timeout(10_000, TimeUnit.MILLISECONDS)
        return request
    }

    private fun addContentType(request: HttpRequest, jettyRequest: Request) {
        val contentType = request.headers["Content-Type"] ?: "application/x-www-form-urlencoded"
        jettyRequest.headers { it.put("Content-Type", contentType) }
    }

    private fun logRequest(request: Request, response: ContentResponse, startTime: Long, content: String? = null) {
        val executionTimeMs = (System.nanoTime() - startTime) / 1_000_000f
        val sb = StringBuilder()
        sb.append(request.method)
        sb.append(" " + request.uri)
        sb.append(" Response: " + response.status)
        sb.append(" (" + executionTimeMs + "ms)")
        if (response.status != 200) {
            sb.appendLine()
            sb.append("Request Body: $content")
            sb.appendLine()
            sb.append("Response Body: " + response.contentAsString)
            logger.error(sb.toString())
            return
        }
        logger.info(sb.toString())
    }

    private fun addRequestHeaders(request: HttpRequest, jettyRequest: Request) {
        request.headers.forEach { (name, value) ->
            if (name == "Content-Type") return@forEach
            jettyRequest.headers { it.put(name, value) }
        }
    }
}
