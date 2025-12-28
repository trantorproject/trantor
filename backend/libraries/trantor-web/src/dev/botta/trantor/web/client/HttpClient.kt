package dev.botta.trantor.web.client

abstract class HttpClient {
    abstract fun post(request: HttpRequest): HttpResponse
    fun post(url: String, body: String? = null, headers: Map<String, String> = mapOf()) = post(HttpRequest(url, body, headers))
    abstract fun put(request: HttpRequest): HttpResponse
    fun put(url: String, body: String? = null, headers: Map<String, String> = mapOf()) = put(HttpRequest(url, body, headers))
    abstract fun patch(request: HttpRequest): HttpResponse
    fun patch(url: String, body: String? = null, headers: Map<String, String> = mapOf()) = patch(HttpRequest(url, body, headers))
    abstract fun delete(request: HttpRequest): HttpResponse
    fun delete(url: String, body: String? = null, headers: Map<String, String> = mapOf()) = delete(HttpRequest(url, body, headers))
    abstract fun get(request: HttpRequest): HttpResponse
    fun get(url: String, headers: Map<String, String> = mapOf()) = get(HttpRequest(url, null, headers))
}
