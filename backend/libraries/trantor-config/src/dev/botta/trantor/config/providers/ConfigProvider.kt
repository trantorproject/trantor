package dev.botta.trantor.config.providers

interface ConfigProvider {
    val paths: List<String>
    fun has(path: String): Boolean
    fun get(path: String): String?
    fun load(forceReload: Boolean = false)
}
