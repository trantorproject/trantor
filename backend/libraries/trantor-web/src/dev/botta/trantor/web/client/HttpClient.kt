package dev.botta.trantor.web.client

abstract class HttpClient {
    abstract suspend fun post(request: HttpRequest): HttpResponse
    suspend fun post(url: String, body: String? = null, headers: Map<String, String> = mapOf()) = post(HttpRequest(url, body, headers))
    abstract suspend fun put(request: HttpRequest): HttpResponse
    suspend fun put(url: String, body: String? = null, headers: Map<String, String> = mapOf()) = put(HttpRequest(url, body, headers))
    abstract suspend fun patch(request: HttpRequest): HttpResponse
    suspend fun patch(url: String, body: String? = null, headers: Map<String, String> = mapOf()) = patch(HttpRequest(url, body, headers))
    abstract suspend fun delete(request: HttpRequest): HttpResponse
    suspend fun delete(url: String, body: String? = null, headers: Map<String, String> = mapOf()) = delete(HttpRequest(url, body, headers))
    abstract suspend fun get(request: HttpRequest): HttpResponse
    suspend fun get(url: String, headers: Map<String, String> = mapOf()) = get(HttpRequest(url, null, headers))
}
