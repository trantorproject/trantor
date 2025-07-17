package dev.botta.trantor.config

class RequiredConfigError(val path: String): Exception("Missing required config $path")
