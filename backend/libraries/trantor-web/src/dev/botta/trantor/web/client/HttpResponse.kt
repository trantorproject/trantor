package dev.botta.trantor.web.client

import java.nio.charset.Charset

class HttpResponse(
    val status: Int,
    val bodyBytes: ByteArray,
    val mediaType: String,
    val encoding: String? = null,
    val headers: Map<String, String> = mapOf(),
) {
    val body by lazy {
        if (encoding == null)
            String(bodyBytes, Charset.forName("utf8"))
        else {
            String(bodyBytes, Charset.forName(encoding))
        }
    }
}
