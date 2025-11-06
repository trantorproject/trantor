package dev.botta.trantor.config

import dev.botta.trantor.config.serviceProvider.ConfigServiceValueResolver
import dev.botta.trantor.core.serialization.JsonSerializer
import dev.botta.trantor.serviceProvider.*

fun <TService: Any> ServiceRegistry.addConfig(serviceType: Class<TService>, configSection: String, key: String? = null) = apply {
    addSingleton(
        serviceType,
        {
            val jsonSerializer = it.get<JsonSerializer>()
            if (!it.config.hasSection(configSection)) return@addSingleton jsonSerializer.deserialize("{}", serviceType)
            val section = it.config.getSection(configSection)
            jsonSerializer.deserialize(section.toJson().toString(), serviceType)
        },
        key,
    )
}

inline fun <reified TService: Any> ServiceRegistry.addConfig(configSection: String, key: String? = null) = apply {
    addConfig(TService::class.java, configSection, key)
}

fun ServiceRegistry.addConfigServiceValueResolver() = apply {
    addSingleton<ServiceValueResolver, ConfigServiceValueResolver>()
}
