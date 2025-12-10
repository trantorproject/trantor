package dev.botta.trantor.domain.errors

open class InvalidArgumentError(val name: String, message: String = "Invalid argument $name", cause: Throwable? = null): DomainError(message, cause)
