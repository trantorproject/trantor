package dev.botta.trantor.config

interface Config {
    operator fun get(path: String): String?
    fun getSection(path: String): ConfigSection
    fun getChildren(): List<ConfigSection>
}
