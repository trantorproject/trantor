package dev.botta.trantor.config

import dev.botta.trantor.config.providers.ConfigProvider

class ConfigManager: Config {
    private val root = ConfigRoot()

    fun add(provider: ConfigProvider) {
        root.add(provider)
    }

    override fun get(path: String) = root[path]

    override fun getSection(path: String) = root.getSection(path)

    override fun getChildren() = root.getChildren()

    override fun toJson() = root.toJson()
}
