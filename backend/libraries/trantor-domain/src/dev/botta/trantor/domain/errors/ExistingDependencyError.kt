package dev.botta.trantor.domain.errors

open class ExistingDependencyError(message: String = "Existing dependency"): DomainError(message)
