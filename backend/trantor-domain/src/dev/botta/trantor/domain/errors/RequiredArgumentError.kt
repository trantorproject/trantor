package dev.botta.trantor.domain.errors

open class RequiredArgumentError(
    name: String,
    message: String = "Argument $name is required",
    cause: Throwable? = null,
): InvalidArgumentError(name, message, cause)
