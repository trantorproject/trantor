package dev.botta.trantor.web.client.jetty

import dev.botta.trantor.web.client.*
import org.eclipse.jetty.client.HttpResponseException
import org.eclipse.jetty.client.api.*
import org.eclipse.jetty.client.util.StringRequestContent
import org.eclipse.jetty.http.HttpMethod
import org.slf4j.LoggerFactory
import java.util.concurrent.*
import org.eclipse.jetty.client.HttpClient as JettyHttp

class JettyHttpClient(maxConnectionsPerDestination: Int = 1200): HttpClient {
    private val logger = LoggerFactory.getLogger(javaClass.simpleName)
    private val httpClient = JettyHttp()

    init {
        httpClient.maxConnectionsPerDestination = maxConnectionsPerDestination
        httpClient.start()
    }

    override fun post(request: HttpRequest): HttpResponse {
        val jettyRequest = createRequest(request.url)
        jettyRequest.method(HttpMethod.POST)
        addContentType(request, jettyRequest)
        addRequestHeaders(request, jettyRequest)
        val content = request.body ?: ""
        jettyRequest.body(StringRequestContent(content))
        val startTime = System.nanoTime()
        try {
            val response = jettyRequest.send()
            logRequest(jettyRequest, response, startTime, content)
            return HttpResponse(response.status, response.contentAsString)
        } catch (e: HttpResponseException) {
            logger.error("""${jettyRequest.method} ${jettyRequest.uri}""")
            throw HttpClientError(e.message, e)
        } catch (e: TimeoutException) {
            logger.error("""${jettyRequest.method} ${jettyRequest.uri}""")
            throw e
        }
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

    override fun get(request: HttpRequest): HttpResponse {
        val jettyRequest = createRequest(request.url)
        jettyRequest.method(HttpMethod.GET)
        addRequestHeaders(request, jettyRequest)
        val startTime = System.nanoTime()
        val response = jettyRequest.send()
        logRequest(jettyRequest, response, startTime)
        return HttpResponse(response.status, response.contentAsString)
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
