package dev.botta.trantor.appServices.auth

import dev.botta.cqbus.ExecutionContext
import dev.botta.cqbus.identity.Identity
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.appServices.AppModule

class SystemIdentity: Identity {
    override val authenticationType: String? = null
    override val isAuthenticated = true
    override val name = "system"
    override val properties = mapOf<String, Any>()
    override val roles = listOf("system")
}

fun <T: Request<R>, R> AppModule.executeAsSystem(request: T): R =
    execute(request, ExecutionContext().withIdentity(SystemIdentity()))
