package dev.botta.trantor.config

class DefaultConfigSection(private val root: ConfigRoot, override val path: String): ConfigSection {
    override val key: String by lazy { path.split(":").last() }

    override val value: String?
        get() = root[path]

    override fun get(path: String) = root["$path:$key"]

    override fun getSection(path: String) = root.getSection("$path:$key")

    override fun getChildren(): List<ConfigSection> {
        return root.getChildren(path)
    }
}
