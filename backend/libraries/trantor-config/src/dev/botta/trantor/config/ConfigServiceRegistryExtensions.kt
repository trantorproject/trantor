package dev.botta.trantor.config

import dev.botta.trantor.core.serialization.JsonSerializer
import dev.botta.trantor.serviceProvider.*

fun <TService: Any> ServiceRegistry.addConfig(serviceType: Class<TService>, configSection: String, key: String? = null) = apply {
    addSingleton(
        serviceType,
        { provider ->
            val jsonSerializer = provider.get<JsonSerializer>()
            val config = provider.get<Config>()
            if (!config.hasSection(configSection)) throw Exception("Config section '$configSection' does not exist")
            val section = config.getSection(configSection)
            jsonSerializer.deserialize(section.toJson().toString(), serviceType)
        },
        key,
    )
}

inline fun <reified TService: Any> ServiceRegistry.addConfig(configSection: String, key: String? = null) = apply {
    addConfig(TService::class.java, configSection, key)
}
