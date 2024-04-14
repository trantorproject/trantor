package dev.botta.trantor.web.client

interface HttpClient {
    fun post(request: HttpRequest): HttpResponse
    fun get(request: HttpRequest): HttpResponse
}
