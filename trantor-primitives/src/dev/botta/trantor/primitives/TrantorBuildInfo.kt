package dev.botta.trantor.primitives

import java.util.*

object TrantorBuildInfo {
    private val props: Properties by lazy {
        Properties().apply {
            Thread.currentThread().contextClassLoader.getResourceAsStream("META-INF/trantor-build-info.properties")?.use { load(it) }
        }
    }

    val version: String get() = props.getProperty("build.version") ?: "UNKNOWN"
}
