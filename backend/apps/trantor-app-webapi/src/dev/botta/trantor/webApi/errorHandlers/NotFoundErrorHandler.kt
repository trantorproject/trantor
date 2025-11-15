package dev.botta.trantor.webApi.errorHandlers

import dev.botta.trantor.webApi.BaseWebApi
import io.ktor.http.*
import kotlin.reflect.KClass

class NotFoundErrorHandler<T: Throwable>(override val errorType: KClass<T>): BaseJsonErrorHandler<T>() {
    override val status = HttpStatusCode.NotFound
}

inline fun <reified T: Exception> BaseWebApi.addNotFoundError() {
    addErrorHandler(NotFoundErrorHandler(T::class))
}
