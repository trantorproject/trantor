package dev.botta.trantor.web.client

import dev.botta.trantor.primitives.lang.describe

class HttpRequest(val url: String, var body: Any? = null, headers: Map<String, String> = mutableMapOf()) {
    val headers = headers.toMutableMap()

    fun setHeader(header: String, value: String) {
        headers[header] = value
    }

    override fun toString() = describe("url=$url", "body=$body", "headers=$headers")

    override fun equals(other: Any?) =
        other is HttpRequest &&
        other.url == url &&
        other.body == body &&
        other.headers == headers

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + (body?.hashCode() ?: 0)
        result = 31 * result + headers.hashCode()
        return result
    }
}
