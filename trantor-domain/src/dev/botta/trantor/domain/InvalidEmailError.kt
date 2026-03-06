package dev.botta.trantor.domain

import dev.botta.trantor.domain.errors.DomainError

class InvalidEmailError(value: String, cause: Throwable? = null): DomainError("Email '$value' is invalid", cause)
