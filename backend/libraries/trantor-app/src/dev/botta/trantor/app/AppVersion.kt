package dev.botta.trantor.app

import java.util.jar.Manifest

class AppVersion {
    private val version: String by lazy { findVersion() }

    private fun findVersion() = try {
        val manifest = Manifest(javaClass.classLoader.getResourceAsStream("META-INF/MANIFEST.MF"))
        manifest.mainAttributes.getValue("VERSION") ?: "DEVELOPMENT"
    } catch (e: Exception) {
        "DEVELOPMENT"
    }

    override fun toString() = version
}
