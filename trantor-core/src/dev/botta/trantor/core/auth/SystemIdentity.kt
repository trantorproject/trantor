package dev.botta.trantor.core.auth

import dev.botta.cqbus.identity.Identity

class SystemIdentity: Identity {
    override val authenticationType: String? = null
    override val isAuthenticated = true
    override val name = "system"
    override val properties = mapOf<String, Any>()
    override val roles = listOf("system")
}
