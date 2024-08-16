package dev.botta.trantor.config

import dev.botta.json.Json
import dev.botta.json.values.JsonValue

private const val CONFIG_TYPE_KEY = "__config_type__"

class DefaultConfigSection(private val root: ConfigRoot, override val path: String): ConfigSection {
    override val key: String by lazy { path.split(".").last() }

    override val value: String?
        get() = root[path]

    override fun get(path: String) = root[getSubPath(path)]

    override fun hasSection(path: String) = root.hasSection(getSubPath(path))

    override fun getSection(path: String) = root.getSection(getSubPath(path))

    override fun getChildren() = root.getChildren(path)

    private fun isArray() = get(CONFIG_TYPE_KEY) == "array"

    private fun getSubPath(otherPath: String) = if (path.isEmpty()) otherPath else "$path.$otherPath"

    override fun toJson(): JsonValue {
        val childrenJson = getChildren().associate { it.key to it.toJson() }
        if (childrenJson.isEmpty()) return Json.value(value)
        if (isArray()) {
            val items = childrenJson
                .filter { it.key != CONFIG_TYPE_KEY && !it.key.equals("size", ignoreCase = true) }
                .values
                .toList()
            return Json.array(items)
        }
        return Json.obj(childrenJson.toList())
    }

    override fun toString() = "ConfigSection($path)"
}
