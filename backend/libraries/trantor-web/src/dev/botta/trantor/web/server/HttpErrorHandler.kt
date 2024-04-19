package dev.botta.trantor.web.server

import io.javalin.http.Context
import org.slf4j.Logger

fun interface HttpErrorHandler<T: Exception> {
    fun handle(error: T, ctx: Context, logger: Logger)
}
