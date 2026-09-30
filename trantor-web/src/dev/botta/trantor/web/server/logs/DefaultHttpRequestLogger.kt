package dev.botta.trantor.web.server.logs

import io.javalin.http.*
import org.fusesource.jansi.Ansi
import org.slf4j.Logger

class DefaultHttpRequestLogger(private val logger: Logger): HttpRequestLogger {
    var maxBodyLogSize: Long = 50_000

    private fun colored(value: Any, color: Ansi.Color): String {
        return Ansi.ansi().fg(color).a(value).reset().toString()
    }

    private fun coloredStatus(ctx: Context): String {
        return when(ctx.status()) {
            HttpStatus.FOUND,
            HttpStatus.OK,
            HttpStatus.ACCEPTED,
            HttpStatus.PERMANENT_REDIRECT,
            HttpStatus.TEMPORARY_REDIRECT,
            HttpStatus.CREATED -> colored(ctx.status(), Ansi.Color.GREEN)

            HttpStatus.CONTINUE,
            HttpStatus.PROCESSING,
            HttpStatus.PARTIAL_CONTENT,
            HttpStatus.NOT_MODIFIED,
            HttpStatus.USE_PROXY,
            HttpStatus.UPGRADE_REQUIRED,
            HttpStatus.NO_CONTENT -> colored(ctx.status(), Ansi.Color.YELLOW)

            else -> colored(ctx.status(), Ansi.Color.RED)
        }
    }

    override fun handle(ctx: Context, executionTimeMs: Float) {
        val sb = StringBuilder()
        sb.append(coloredStatus(ctx) + ": ")
        sb.append(colored(ctx.req().method, Ansi.Color.CYAN))
        sb.append(" - " + ctx.loggableUrl())
        sb.append(" in " + executionTimeMs + "ms")
        if (ctx.statusCode() < 200 || ctx.statusCode() >= 400) {
            val contentType = ctx.req().contentType ?: ""
            val contentLength = ctx.req().contentLength
            val requestBody = if (contentType.startsWith("multipart/form-data")) {
                "Multipart (${contentLength} bytes)"
            } else if (contentLength <= 0) {
                ""
            } else if (contentLength > maxBodyLogSize) {
                "$contentLength bytes"
            } else {
                ctx.body()
            }
            if (requestBody.isNotEmpty()) {
                sb.appendLine()
                sb.append("Request Body: $requestBody")
            }
//            sb.appendLine()
//            sb.append("Response Body: " + ctx.result())
            logger.error(sb.toString())
            return
        }
        logger.info(sb.toString())
    }
}
