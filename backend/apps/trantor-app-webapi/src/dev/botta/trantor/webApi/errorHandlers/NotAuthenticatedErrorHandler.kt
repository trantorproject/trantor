package dev.botta.trantor.webApi.errorHandlers

import dev.botta.trantor.webApi.BaseWebApi
import io.ktor.http.*
import kotlin.reflect.KClass

class NotAuthenticatedErrorHandler<T: Exception>(override val errorType: KClass<T>): BaseJsonErrorHandler<T>() {
    override val status = HttpStatusCode.Unauthorized
}

inline fun <reified T: Exception> BaseWebApi.addNotAuthenticatedError() {
    addErrorHandler(NotAuthenticatedErrorHandler(T::class))
}
