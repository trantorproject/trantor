package dev.botta.trantor.config.providers

import dev.botta.trantor.config.ConfigManager
import java.util.*

class PropertiesResourceConfigProvider(private val resourceName: String): ConfigProviderBase() {
    override fun load() {
        val resource = ClassLoader.getSystemResource(resourceName) ?: return
        val properties = Properties()
        resource.openStream().use { properties.load(it) }
        for (name in properties.stringPropertyNames()) {
            set(name, properties.getProperty(name))
        }
    }
}

fun ConfigManager.addPropertiesResource(resourceName: String) = apply {
    add(PropertiesResourceConfigProvider(resourceName))
}
