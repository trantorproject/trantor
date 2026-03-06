package dev.botta.trantor.domain.errors

open class InvalidOperationError(message: String = "Invalid Operation", cause: Throwable? = null):
    DomainError(message, cause)
