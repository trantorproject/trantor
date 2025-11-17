package dev.botta.trantor.config.providers

import dev.botta.env.Env
import dev.botta.trantor.config.ConfigManager

class EnvironmentVariablesConfigProvider(private val prefix: String = ""): ConfigProviderBase() {
    override fun load() {
        Env.getAll()
            .filter { it.name.startsWith(prefix, ignoreCase = true) }
            .forEach {
                val name = it.name.removePrefix(prefix)
                set(name, it.value)
                if (name.contains("_")) {
                    val normalizedName = name.split("__").joinToString(".") { part -> underscoreToCamelCase(part) }
                    set(normalizedName, it.value)
                }
            }
    }

    private fun underscoreToCamelCase(input: String): String {
        if (input.isEmpty()) return input
        val parts = input.split('_')
        return buildString {
            append(parts.first().lowercase())
            for (p in parts.drop(1)) {
                if (p.isNotEmpty()) {
                    append(p[0].uppercase())
                    append(p.substring(1).lowercase())
                }
            }
        }
    }
}

fun ConfigManager.addEnvironmentVariables(prefix: String = "") = apply {
    add(EnvironmentVariablesConfigProvider(prefix))
}
