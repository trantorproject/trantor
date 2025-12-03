package dev.botta.trantor.web.server.logs

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import kotlinx.coroutines.runBlocking
import org.fusesource.jansi.Ansi

class DefaultHttpRequestLogger: HttpRequestLogger {
    var maxBodyLogSize: Long = 50_000

    override fun handle(call: ApplicationCall): String {
        val status = call.response.status() ?: "Unhandled"
        return when (status) {
            HttpStatusCode.Found -> "${coloredStatus(status as HttpStatusCode)}: " +
                    "${call.toShortLogString()} -> ${call.response.headers[HttpHeaders.Location]}"

            "Unhandled" -> "${colored(status, Ansi.Color.RED)}: ${call.toLogString()}"
            else -> "${coloredStatus(status as HttpStatusCode)}: ${call.toLogString()}"
        }
    }

    private fun coloredStatus(status: HttpStatusCode): String {
        return when (status) {
            HttpStatusCode.Found,
            HttpStatusCode.OK,
            HttpStatusCode.Accepted,
            HttpStatusCode.Created -> colored(status, Ansi.Color.GREEN)

            HttpStatusCode.Continue,
            HttpStatusCode.Processing,
            HttpStatusCode.PartialContent,
            HttpStatusCode.NotModified,
            HttpStatusCode.UseProxy,
            HttpStatusCode.UpgradeRequired,
            HttpStatusCode.NoContent -> colored(status, Ansi.Color.YELLOW)

            else -> colored(status, Ansi.Color.RED)
        }
    }

    private fun colored(value: Any, color: Ansi.Color): String {
        return Ansi.ansi().fg(color).a(value).reset().toString()
    }

    private fun ApplicationCall.toShortLogString(): String =
        "${colored(request.httpMethod.value, Ansi.Color.CYAN)} - ${request.path()} in ${processingTimeMillis()}ms"

    private fun ApplicationCall.toLogString(): String {
        val sb = StringBuilder()
        sb.append(colored(request.httpMethod.value, Ansi.Color.CYAN))
        sb.append(" - ")
        sb.append(request.path())
        sb.append(" in ${processingTimeMillis()}ms")
        val statusCode = response.status()?.value ?: 0
        if (statusCode >= 300 || statusCode < 200) {
            val contentType = request.contentType().toString()
            val contentLength = request.headers["Content-Length"]?.toLongOrNull() ?: -1L
            val requestBody = if (contentType.startsWith("multipart/form-data")) {
                "Multipart (${contentLength} bytes)"
            } else if (contentLength == -1L) {
                ""
            } else if (contentLength > maxBodyLogSize) {
                "$contentLength bytes"
            } else {
                runBlocking { receiveText() }
            }
            if (requestBody.isNotEmpty()) {
                sb.appendLine()
                sb.append("Request Body: $requestBody")
            }
        }
        return sb.toString()
    }
}
