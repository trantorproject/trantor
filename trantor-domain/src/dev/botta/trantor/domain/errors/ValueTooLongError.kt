package dev.botta.trantor.domain.errors

class ValueTooLongError(message: String = "Value too long", cause: Throwable? = null): DomainError(message, cause)
