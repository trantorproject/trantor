package dev.botta.trantor.webApi.errorHandlers

import dev.botta.trantor.webApi.WebApi
import kotlin.reflect.KClass

class NotFoundErrorHandler<T: Exception>(override val errorType: Class<T>): BaseJsonErrorHandler<T>() {
    override val status = 404

    constructor(errorType: KClass<T>): this(errorType.java)
}

inline fun <reified T: Exception> WebApi.addNotFoundError() {
    addErrorHandler(NotFoundErrorHandler(T::class))
}
