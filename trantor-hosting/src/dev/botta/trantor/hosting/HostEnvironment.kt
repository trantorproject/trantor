package dev.botta.trantor.hosting


class HostEnvironment(environmentName: String, val appName: String) {
    val environmentName = environmentName.uppercase()
    val isDevelopment get() = isEnvironment("DEVELOPMENT")
    val isStaging get() = isEnvironment("STAGING")
    val isProduction get() = isEnvironment("PRODUCTION")

    fun isEnvironment(environment: String) = environmentName == environment.uppercase()

    override fun toString() = "$appName - $environmentName"
}
