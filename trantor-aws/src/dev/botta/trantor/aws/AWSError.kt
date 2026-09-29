package dev.botta.trantor.aws

open class AWSError(message: String, cause: Throwable? = null): RuntimeException(message, cause)
