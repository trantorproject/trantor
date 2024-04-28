package dev.botta.trantor.webApi.errorHandlers

import dev.botta.trantor.webApi.BaseWebApi
import kotlin.reflect.KClass

class BadRequestErrorHandler<T: Exception>(override val errorType: Class<T>): BaseJsonErrorHandler<T>() {
    override val status = 400

    constructor(errorType: KClass<T>): this(errorType.java)
}

inline fun <reified T: Exception> BaseWebApi.addBadRequestError() {
    addErrorHandler(BadRequestErrorHandler(T::class))
}
