package dev.botta.trantor.config

/** A value refers to another that, following the references, refers back to it, so none of them has a value. */
class ConfigInterpolationError(message: String): RuntimeException(message)
