package dev.botta.trantor.core

import dev.botta.trantor.config.ConfigManager

data class ApplicationBuilderConfig(
    var args: Array<String> = arrayOf(),
    var environmentName: String? = null,
    var appName: String? = null,
    var config: ConfigManager? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ApplicationBuilderConfig

        if (!args.contentEquals(other.args)) return false
        if (environmentName != other.environmentName) return false
        if (appName != other.appName) return false
        if (config != other.config) return false

        return true
    }

    override fun hashCode(): Int {
        var result = args.contentHashCode()
        result = 31 * result + (environmentName?.hashCode() ?: 0)
        result = 31 * result + (appName?.hashCode() ?: 0)
        result = 31 * result + (config?.hashCode() ?: 0)
        return result
    }
}
