package dev.botta.trantor.serviceProvider

import dev.botta.trantor.config.Config
import dev.botta.trantor.core.serialization.JsonSerializer

fun <TService: Any> ServiceRegistry.configure(serviceType: Class<TService>, config: Config, key: String? = null) = apply {
    addSingleton(
        serviceType,
        { provider ->
            val jsonSerializer = provider.get<JsonSerializer>()
            jsonSerializer.deserialize(config.toJson().toString(), serviceType)
        },
        key,
    )
}

inline fun <reified TService: Any> ServiceRegistry.configure(config: Config) = apply {
    configure(TService::class.java, config, null)
}

inline fun <reified TService: Any> ServiceRegistry.configure(key: String, config: Config) = apply {
    configure(TService::class.java, config, key)
}
