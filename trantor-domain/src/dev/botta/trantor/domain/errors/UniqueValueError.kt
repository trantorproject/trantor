package dev.botta.trantor.domain.errors

class UniqueValueError(val name: String, message: String? = null, cause: Throwable? = null):
    DomainError(message ?: "$name must be unique", cause)
