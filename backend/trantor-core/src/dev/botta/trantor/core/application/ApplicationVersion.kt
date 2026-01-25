package dev.botta.trantor.core.application

import java.util.jar.Manifest

class ApplicationVersion {
    private val version: String by lazy { findVersion() }

    private fun findVersion() = try {
        val manifest = Manifest(javaClass.classLoader.getResourceAsStream("META-INF/MANIFEST.MF"))
        manifest.mainAttributes.getValue("VERSION") ?: "DEVELOPMENT"
    } catch (e: Exception) {
        "DEVELOPMENT"
    }

    override fun toString() = version
}
