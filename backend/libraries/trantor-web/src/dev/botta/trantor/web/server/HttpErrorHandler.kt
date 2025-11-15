package dev.botta.trantor.web.server

import io.ktor.server.application.*
import org.slf4j.Logger
import kotlin.reflect.KClass

interface HttpErrorHandler<T: Throwable> {
    val errorType: KClass<T>

    suspend fun handle(call: ApplicationCall, cause: T, logger: Logger)
}
