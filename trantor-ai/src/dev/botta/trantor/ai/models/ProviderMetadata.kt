package dev.botta.trantor.ai.models

import dev.botta.json.values.JsonObject

/**
 * Provider specific data attached to a part: item ids, reasoning signatures, cache control.
 * Kept per provider so that sending a conversation to a different one doesn't leak the previous provider's data.
 */
class ProviderMetadata private constructor(private val values: Map<String, JsonObject>) {
    val isEmpty get() = values.isEmpty()
    val providers get() = values.keys

    operator fun get(provider: String) = values[provider]

    fun with(provider: String, value: JsonObject) = ProviderMetadata(values + (provider to value))

    override fun equals(other: Any?) = other is ProviderMetadata && other.values == values

    override fun hashCode() = values.hashCode()

    override fun toString() = values.toString()

    companion object {
        val None = ProviderMetadata(emptyMap())

        fun of(provider: String, value: JsonObject) = ProviderMetadata(mapOf(provider to value))
    }
}
