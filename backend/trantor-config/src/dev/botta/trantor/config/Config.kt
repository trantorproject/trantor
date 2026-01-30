package dev.botta.trantor.config

import dev.botta.json.values.JsonValue

interface Config {
    operator fun get(path: String): String?
    fun has(path: String): Boolean
    fun required(path: String): String
    fun hasSection(path: String): Boolean
    fun getSection(path: String): ConfigSection
    fun getChildren(): List<ConfigSection>
    fun toJson(): JsonValue
}
