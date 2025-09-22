package dev.botta.trantor.web.server.logs

import io.javalin.http.Context
import org.slf4j.Logger

class DefaultHttpRequestLogger(private val logger: Logger): HttpRequestLogger {
    override fun handle(ctx: Context, executionTimeMs: Float) {
        val sb = StringBuilder()
        sb.append(ctx.req().method)
        sb.append(" " + ctx.fullUrl())
        sb.append(" Response: " + ctx.res().status)
        sb.append(" - " + ctx.res().getHeader("content-type"))
        sb.append(" (" + executionTimeMs + "ms)")
        if (ctx.res().status >= 300 || ctx.res().status < 200) {
            sb.appendLine()
            if (ctx.req().contentType == "multipart/form-data") {
                sb.append("Request Body: Multipart ${ctx.req().contentLength} bytes")
            } else if (ctx.req().contentLength > 1_000_000) {
                sb.append("Request Body: ${ctx.req().contentLength} bytes")
            } else {
                sb.append("Request Body: " + ctx.body())
            }
            sb.appendLine()
            sb.append("Response Body: " + ctx.result())
            logger.error(sb.toString())
            return
        }
        logger.info(sb.toString())
    }
}
