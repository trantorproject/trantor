package dev.botta.trantor.domain.errors

open class DomainError(message: String, cause: Throwable? = null): Exception(message, cause)
