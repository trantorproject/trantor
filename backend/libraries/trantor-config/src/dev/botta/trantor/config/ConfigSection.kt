package dev.botta.trantor.config

interface ConfigSection: Config {
    val key: String
    val path: String
    val value: String?
    fun toJson(): String
}
