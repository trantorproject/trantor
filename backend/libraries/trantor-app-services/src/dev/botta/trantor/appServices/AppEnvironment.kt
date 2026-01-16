package dev.botta.trantor.appServices

class AppEnvironment(environmentName: String, val appName: String) {
    val environmentName = environmentName.uppercase()
    val isDevelopment get() = isEnvironment("DEVELOPMENT")
    val isStaging get() = isEnvironment("STAGING")
    val isProduction get() = isEnvironment("PRODUCTION")

    fun isEnvironment(environment: String) = environment == environment.uppercase()

    override fun toString() = "$appName - $environmentName"
}
