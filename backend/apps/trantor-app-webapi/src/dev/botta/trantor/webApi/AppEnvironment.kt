package dev.botta.trantor.webApi

class AppEnvironment(environment: String, val appName: String) {
    val environment = environment.uppercase()
    val isDevelopment get() = isEnvironment("DEVELOPMENT")
    val isStaging get() = isEnvironment("STAGING")
    val isProduction get() = isEnvironment("PRODUCTION")

    fun isEnvironment(environment: String) = environment == environment.uppercase()

    override fun toString() = "$appName - $environment"
}
