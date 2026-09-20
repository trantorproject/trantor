package dev.botta.trantor.config.providers

import dev.botta.env.Env
import dev.botta.env.EnvVar
import dev.botta.trantor.config.ConfigManager

/**
 * Configuration taken from the environment.
 *
 * A variable is kept under its own name, and also under a path: `__` becomes a dot and each `_` separated
 * word becomes camel case, so `DB__CONNECTION_STRING` can be read as `db.connectionString`. That is what
 * lets a deployment override any setting without the application knowing it came from the environment.
 *
 * The [prefix] is stripped before the name is read, so `TRANTOR_DB__URL` with prefix `TRANTOR_` is `db.url`.
 */
class EnvironmentVariablesConfigProvider(
    private val prefix: String = "",
    private val variables: () -> List<EnvVar> = { Env.getAll() },
): ConfigProviderBase() {
    override fun load() {
        variables()
            .filter { it.name.startsWith(prefix, ignoreCase = true) }
            .forEach {
                // Not removePrefix: the filter above ignores case, so the removal has to as well
                val name = it.name.substring(prefix.length)
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
