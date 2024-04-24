package dev.botta.trantor.webApi.errorHandlers

import dev.botta.trantor.webApi.WebApi
import kotlin.reflect.KClass

class ForbiddenErrorHandler<T: Exception>(override val errorType: Class<T>): BaseJsonErrorHandler<T>() {
    override val status = 403

    constructor(errorType: KClass<T>): this(errorType.java)
}

inline fun <reified T: Exception> WebApi.addForbiddenError() {
    addErrorHandler(ForbiddenErrorHandler(T::class))
}
