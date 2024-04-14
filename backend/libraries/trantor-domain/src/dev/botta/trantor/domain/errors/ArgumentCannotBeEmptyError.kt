package dev.botta.trantor.domain.errors

open class ArgumentCannotBeEmptyError(name: String, message: String = "Argument $name cannot be empty"): InvalidArgumentError(name, message)
