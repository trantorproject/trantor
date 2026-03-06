package dev.botta.trantor.domain.errors

open class NotFoundError(message: String = "Not found", cause: Throwable? = null): DomainError(message, cause)
