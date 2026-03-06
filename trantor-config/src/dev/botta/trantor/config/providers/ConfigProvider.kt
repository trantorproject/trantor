package dev.botta.trantor.config.providers

interface ConfigProvider {
    val paths: List<String>
    fun has(key: String): Boolean
    fun hasSection(path: String): Boolean
    fun get(path: String): String?
    fun load()
}
