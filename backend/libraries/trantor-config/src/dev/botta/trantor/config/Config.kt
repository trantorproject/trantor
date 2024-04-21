package dev.botta.trantor.config

import dev.botta.json.values.JsonValue

interface Config {
    operator fun get(path: String): String?
    fun getSection(path: String): ConfigSection
    fun getChildren(): List<ConfigSection>
    fun toJson(): JsonValue
}
