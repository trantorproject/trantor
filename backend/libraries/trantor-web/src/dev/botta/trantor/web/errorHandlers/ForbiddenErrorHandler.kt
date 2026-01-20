package dev.botta.trantor.web.errorHandlers

import dev.botta.trantor.web.WebApplication
import kotlin.reflect.KClass

class ForbiddenErrorHandler<T: Exception>(override val errorType: Class<T>): BaseJsonErrorHandler<T>() {
    override val status = 403

    constructor(errorType: KClass<T>): this(errorType.java)
}

inline fun <reified T: Exception> WebApplication.addForbiddenError() {
    addErrorHandler(ForbiddenErrorHandler(T::class))
}
