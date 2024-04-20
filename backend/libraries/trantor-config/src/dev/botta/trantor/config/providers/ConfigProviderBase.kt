package dev.botta.trantor.config.providers

abstract class ConfigProviderBase: ConfigProvider {
    private val data: MutableMap<String, String?> = mutableMapOf()
    override val paths get() = data.keys.toList()

    override fun has(path: String) = data.contains(path.lowercase())

    override fun get(path: String) = data[path.lowercase()]

    protected fun set(path: String, value: String?) {
        data[path.lowercase()] = value
    }
}
