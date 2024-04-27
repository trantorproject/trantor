package dev.botta.trantor.config.providers

import dev.botta.json.Json
import dev.botta.json.values.*
import dev.botta.trantor.config.ConfigManager

class JsonResourceConfigProvider(private val resourceName: String): ConfigProviderBase() {
    override fun load() {
        val resource = ClassLoader.getSystemResource(resourceName) ?: return
        val contents = resource.readText()
        val json = Json.parse(contents).asObject() ?: return
        setProperties(json)
    }

    private fun setValue(json: JsonValue, key: String) {
        if (json.isNull) {
            set(key, null)
            return
        }
        when (json) {
            is JsonObject -> setProperties(json, "$key.")
            is JsonArray -> setArrayItems(json, "$key.")
            is JsonString -> set(key, json.asString())
            else -> set(key, json.toString())
        }
    }

    private fun setProperties(json: JsonObject, path: String = "") {
        json.forEach { property, value -> setValue(value, "$path$property") }
    }

    private fun setArrayItems(json: JsonArray, path: String = "") {
        set("${path}__config_type__", "array")
        set("${path}size", json.size.toString())
        json.forEachIndexed { index, value -> setValue(value, "$path$index") }
    }
}

fun ConfigManager.addJsonResource(resourceName: String) = apply {
    add(JsonResourceConfigProvider(resourceName))
}
