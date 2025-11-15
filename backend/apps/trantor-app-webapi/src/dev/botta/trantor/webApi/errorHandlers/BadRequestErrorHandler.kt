package dev.botta.trantor.webApi.errorHandlers

import dev.botta.trantor.webApi.BaseWebApi
import io.ktor.http.*
import kotlin.reflect.KClass

class BadRequestErrorHandler<T: Exception>(override val errorType: KClass<T>): BaseJsonErrorHandler<T>() {
    override val status = HttpStatusCode.BadRequest
}

inline fun <reified T: Exception> BaseWebApi.addBadRequestError() {
    addErrorHandler(BadRequestErrorHandler(T::class))
}
