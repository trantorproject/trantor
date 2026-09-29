package dev.botta.trantor.config

class RequiredConfigError(val path: String): RuntimeException("Missing required config $path")
