package dev.botta.trantor.config

import dev.botta.trantor.config.providers.ConfigProvider

// Root node for a configuration
class ConfigRoot(providers: List<ConfigProvider> = listOf()): Config {
    private val providers: MutableList<ConfigProvider> = mutableListOf()

    init {
        providers.forEach { add(it) }
    }

    fun add(provider: ConfigProvider) {
        provider.load()
        providers.add(provider)
    }

    override fun get(path: String): String? {
        for(provider in providers.reversed()) {
           if (provider.has(path)) {
               return provider.get(path)
           }
        }

        return null
    }

    override fun hasSection(path: String) = providers.any { it.hasSection(path) }

    override fun getSection(path: String) = DefaultConfigSection(this, path)

    override fun getChildren() = doGetChildren(null)

    override fun toJson() = getSection("").toJson()

    fun getChildren(path: String) = doGetChildren(path)

    private fun doGetChildren(path: String? = null): List<ConfigSection> {
        val prefix = if (path.isNullOrEmpty()) "" else "$path."
        return providers
            .flatMap { it.paths.filter { p -> p.startsWith(prefix, ignoreCase = true) } }
            .map { it.substring(prefix.length).split(".").first() }
            .distinctBy { it.lowercase() }
            .sorted()
            .map { getSection(prefix + it) }
    }
}
