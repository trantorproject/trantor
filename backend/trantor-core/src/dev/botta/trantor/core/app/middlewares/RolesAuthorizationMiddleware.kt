package dev.botta.trantor.core.app.middlewares

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.core.auth.*

class RolesAuthorizationMiddleware: Middleware {
    override fun <T: Request<R>, R> execute(request: T, next: (T) -> R, context: ExecutionContext): R {
        failIfNotAuthorized(request.javaClass, context)
        return next(request)
    }

    private fun failIfNotAuthorized(clazz: Class<Any>, context: ExecutionContext) {
        val requiredRoles = getRequiredRoles(clazz)
        if (requiredRoles.isEmpty()) return
        val userRoles = getUserRoles(context)
        val hasARequiredRole = userRoles.any { requiredRoles.contains(it) }
        if (!hasARequiredRole) throw UnauthorizedAccessError()
    }

    private fun getRequiredRoles(clazz: Class<Any>) = requiredAuthorizationRoles(clazz).map { it.lowercase() }

    private fun getUserRoles(context: ExecutionContext): List<String> {
        return context.identity.roles.map { it.lowercase() }
    }
}
