package dev.botta.trantor.webApi.errorHandlers

import dev.botta.trantor.webApi.BaseWebApi
import kotlin.reflect.KClass

class NotAuthenticatedErrorHandler<T: Exception>(override val errorType: Class<T>): BaseJsonErrorHandler<T>() {
    override val status = 401

    constructor(errorType: KClass<T>): this(errorType.java)
}

inline fun <reified T: Exception> BaseWebApi.addNotAuthenticatedError() {
    addErrorHandler(NotAuthenticatedErrorHandler(T::class))
}
