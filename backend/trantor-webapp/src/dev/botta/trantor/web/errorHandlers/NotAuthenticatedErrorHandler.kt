package dev.botta.trantor.web.errorHandlers

import dev.botta.trantor.web.WebApplication
import kotlin.reflect.KClass

class NotAuthenticatedErrorHandler<T: Exception>(override val errorType: Class<T>): BaseJsonErrorHandler<T>() {
    override val status = 401

    constructor(errorType: KClass<T>): this(errorType.java)
}

inline fun <reified T: Exception> WebApplication.addNotAuthenticatedError() {
    addErrorHandler(NotAuthenticatedErrorHandler(T::class))
}
