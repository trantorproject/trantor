package dev.botta.trantor.web.errorHandlers

import dev.botta.trantor.web.WebApplication
import kotlin.reflect.KClass

class NotFoundErrorHandler<T: Exception>(override val errorType: Class<T>): BaseJsonErrorHandler<T>() {
    override val status = 404

    constructor(errorType: KClass<T>): this(errorType.java)
}

inline fun <reified T: Exception> WebApplication.addNotFoundError() {
    addErrorHandler(NotFoundErrorHandler(T::class))
}
