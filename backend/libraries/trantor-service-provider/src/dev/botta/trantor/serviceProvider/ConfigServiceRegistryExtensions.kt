package dev.botta.trantor.serviceProvider

import dev.botta.trantor.config.Config
import dev.botta.trantor.core.serialization.JsonSerializer

fun <TService: Any> ServiceRegistry.addConfig(serviceType: Class<TService>, configSection: String, key: String? = null) = apply {
    addSingleton(
        serviceType,
        { provider ->
            val jsonSerializer = provider.get<JsonSerializer>()
            val config = provider.get<Config>()
            val section = config.getSection(configSection)
            jsonSerializer.deserialize(section.toJson().toString(), serviceType)
        },
        key,
    )
}

inline fun <reified TService: Any> ServiceRegistry.addConfig(configSection: String, key: String? = null) = apply {
    addConfig(TService::class.java, configSection, key)
}
