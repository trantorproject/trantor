package dev.botta.trantor.core.validation

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import jakarta.validation.Validator

class ValidationMiddleware(private val validator: Validator): Middleware {
    override fun <T: Request<R>, R> execute(request: T, next: (T) -> R, context: ExecutionContext): R {
        val violations = validator.validate(request)
        if (violations.isNotEmpty()) {
            throw ValidationError(violations.map { v ->
                FieldError(field = v.propertyPath.toString(), message = v.message)
            })
        }
        return next(request)
    }
}
