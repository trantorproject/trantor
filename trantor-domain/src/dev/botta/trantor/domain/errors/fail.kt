package dev.botta.trantor.domain.errors

@Suppress("NOTHING_TO_INLINE")
inline fun fail(message: Any): Nothing {
    throw DomainError(message.toString())
}
