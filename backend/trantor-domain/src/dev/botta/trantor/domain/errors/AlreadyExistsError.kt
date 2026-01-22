package dev.botta.trantor.domain.errors

class AlreadyExistsError(message: String = "Already exists", cause: Throwable? = null): DomainError(message, cause) {
}
