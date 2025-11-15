package dev.botta.trantor.webApi.errorHandlers

import dev.botta.trantor.webApi.BaseWebApi
import io.ktor.http.*
import kotlin.reflect.KClass

class ForbiddenErrorHandler<T: Exception>(override val errorType: KClass<T>): BaseJsonErrorHandler<T>() {
    override val status = HttpStatusCode.Forbidden
}

inline fun <reified T: Exception> BaseWebApi.addForbiddenError() {
    addErrorHandler(ForbiddenErrorHandler(T::class))
}
