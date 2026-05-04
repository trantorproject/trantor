package dev.botta.trantor.domain.errors

inline fun fail(message: Any): Nothing {
    throw DomainError(message.toString())
}
