package dev.botta.trantor.serialization.gson

import dev.botta.trantor.core.serialization.JsonSerializer
import dev.botta.trantor.serviceProvider.*

fun ServiceRegistry.addGsonSerializer(config: ServiceConfiguration<GsonSerializer> = {}) = apply {
    tryAdd(ServiceDescriptor.singleton<JsonSerializer>({ GsonSerializer() }))
    configure<JsonSerializer>{ (it as? GsonSerializer)?.apply(config) }
}
