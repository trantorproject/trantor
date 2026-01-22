package dev.botta.trantor.serialization.gson

import dev.botta.trantor.core.serialization.JsonSerializer
import dev.botta.trantor.di.*

fun ServiceRegistry.addGsonSerializer(config: ServiceConfiguration<GsonSerializer> = { _, _ -> }) = apply {
    configureGsonSerializer(config)
}

fun ServiceRegistry.configureGsonSerializer(config: ServiceConfiguration<GsonSerializer> = { _, _ -> }) = apply {
    addSingletonIfMissing<JsonSerializer> { GsonSerializer() }
    configure<JsonSerializer>{ instance, services ->
        if (instance !is GsonSerializer) return@configure
        config(instance, services)
    }
}
