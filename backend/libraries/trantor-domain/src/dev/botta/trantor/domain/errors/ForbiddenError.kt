package dev.botta.trantor.domain.errors

open class ForbiddenError(message: String = "Not authorized", cause: Throwable? = null): DomainError(message, cause)
