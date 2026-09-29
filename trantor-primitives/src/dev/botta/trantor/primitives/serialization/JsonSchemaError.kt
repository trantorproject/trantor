package dev.botta.trantor.primitives.serialization

/** A type whose JSON Schema cannot be told, with where in it the problem is. */
open class JsonSchemaError(message: String, cause: Throwable? = null): RuntimeException(message, cause)
