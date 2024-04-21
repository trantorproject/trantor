package dev.botta.trantor.config.providers

import java.util.*

abstract class ConfigProviderBase: ConfigProvider {
    private val data: MutableMap<String, String?> = TreeMap(String.CASE_INSENSITIVE_ORDER)
    override val paths get() = data.keys.toList()

    override fun has(path: String) = data.contains(path)

    override fun get(path: String) = data[path]

    protected fun set(path: String, value: String?) {
        data[path] = value
    }
}
