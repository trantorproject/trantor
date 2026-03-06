package dev.botta.trantor.core.application

open class ApplicationError(message: String, cause: Exception? = null): Exception(message, cause)
