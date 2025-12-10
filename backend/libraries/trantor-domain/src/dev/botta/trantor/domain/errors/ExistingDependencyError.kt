package dev.botta.trantor.domain.errors

open class ExistingDependencyError(message: String = "Existing dependency", cause: Throwable? = null): DomainError(message, cause)
