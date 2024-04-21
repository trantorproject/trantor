package dev.botta.trantor.config

class DefaultConfigSection(private val root: ConfigRoot, override val path: String): ConfigSection {
    override val key: String by lazy { path.split(":").last() }

    override val value: String?
        get() = root[path]

    override fun get(path: String) = root["${this.path}:$path"]

    override fun getSection(path: String) = root.getSection("${this.path}:$path")

    override fun getChildren(): List<ConfigSection> {
        return root.getChildren(path)
    }

    override fun toString() = "ConfigSection($path)"

    private fun isArray() = get("__config_type__") == "Array"

    override fun toJson(): String {
        TODO()
    }
}
