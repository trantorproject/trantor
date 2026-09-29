package dev.botta.trantor.domain.errors

class ConcurrentModificationError(message: String = "Concurrent modification", cause: Throwable? = null):
    DomainError(message, cause)
