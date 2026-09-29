package dev.botta.trantor.di

class MustHaveDefaultNoArgsConstructorError(type: Class<*>): RuntimeException("Type ${type.name} must have a default no-args constructor")
