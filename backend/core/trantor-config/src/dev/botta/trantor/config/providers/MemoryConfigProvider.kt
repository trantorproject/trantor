package dev.botta.trantor.config.providers

import dev.botta.trantor.config.ConfigManager

class MemoryConfigProvider(private val initialData: Map<String, String?> = mapOf()): ConfigProviderBase() {
    constructor(vararg pairs: Pair<String, String?>): this(pairs.toMap())

    init {
        initialData.forEach { (k, v) -> set(k, v) }
    }

    override fun load() {
    }
}

fun ConfigManager.addMemoryCollection(vararg pairs: Pair<String, String?>) = apply {
    add(MemoryConfigProvider(*pairs))
}

fun ConfigManager.addMemoryCollection(data: Map<String, String?>) = apply {
    add(MemoryConfigProvider(data))
}
