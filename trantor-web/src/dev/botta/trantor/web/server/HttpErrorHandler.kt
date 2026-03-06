package dev.botta.trantor.web.server

import io.javalin.http.Context
import org.slf4j.Logger

interface HttpErrorHandler<T: Exception> {
    val errorType: Class<T>

    fun handle(error: T, ctx: Context, logger: Logger)
}
