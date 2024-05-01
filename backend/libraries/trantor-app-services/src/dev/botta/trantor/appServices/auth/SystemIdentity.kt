package dev.botta.trantor.appServices.auth

import dev.botta.cqbus.*
import dev.botta.cqbus.identity.Identity
import dev.botta.cqbus.requests.Request

class SystemIdentity: Identity {
    override val authenticationType: String? = null
    override val isAuthenticated = true
    override val name = "system"
    override val properties = mapOf<String, Any>()
    override val roles = listOf("system")
}

fun <T: Request<R>, R> CQBus.executeAsSystem(request: T): R =
    execute(request, ExecutionContext().withIdentity(SystemIdentity()))
