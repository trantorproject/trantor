package dev.botta.trantor.domain

import dev.botta.trantor.domain.errors.DomainError

class InvalidEmailError(value: String): DomainError("Email '$value' is invalid")
