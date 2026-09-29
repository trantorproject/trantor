package dev.botta.trantor.web.errorHandlers

import dev.botta.trantor.web.application.WebApplication
import kotlin.reflect.KClass

class ConflictErrorHandler<T: Exception>(override val errorType: Class<T>): BaseJsonErrorHandler<T>() {
    override val status = 409

    constructor(errorType: KClass<T>): this(errorType.java)
}

inline fun <reified T: Exception> WebApplication.addConflictError() {
    addErrorHandler(ConflictErrorHandler(T::class))
}
