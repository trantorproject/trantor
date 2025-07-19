package dev.botta.trantor.domain.errors

open class ForbiddenError(message: String = "Not authorized"): DomainError(message)
