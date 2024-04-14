package dev.botta.trantor.domain.errors

open class RequiredArgumentError(name: String, message: String = "Argument $name is required"): InvalidArgumentError(name, message)
