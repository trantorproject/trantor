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
                if (name.contains("_")) set(name.replace("_", "."), it.value)
            }
    }
}

fun ConfigManager.addEnvironmentVariables(prefix: String = "") = apply {
    add(EnvironmentVariablesConfigProvider(prefix))
}
