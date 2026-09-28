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

    /**
     * The value of the last provider that has [path], with its references to other keys resolved: see
     * [ConfigInterpolation].
     */
    override fun get(path: String) = get(path, emptyList())

    private fun get(path: String, resolving: List<String>): String? {
        if (resolving.any { it.equals(path, ignoreCase = true) }) {
            throw ConfigInterpolationError(
                "The configuration refers to itself, so it has no value: ${(resolving + path).joinToString(" -> ")}",
            )
        }

        val value = providers.lastOrNull { it.has(path) }?.get(path) ?: return null
        return ConfigInterpolation.resolve(value) { get(it, resolving + path) }
    }

    override fun has(path: String) = providers.any { it.has(path) }

    override fun required(path: String) = get(path) ?: throw RequiredConfigError(path)

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
