package dev.botta.trantor.config.providers

class MemoryConfigProvider(private val initialData: Map<String, String?> = mapOf()): ConfigProviderBase() {
    constructor(vararg pairs: Pair<String, String?>): this(pairs.toMap())

    init {
        initialData.forEach { (k, v) -> set(k, v) }
    }

    override fun load(forceReload: Boolean) {
    }
}
