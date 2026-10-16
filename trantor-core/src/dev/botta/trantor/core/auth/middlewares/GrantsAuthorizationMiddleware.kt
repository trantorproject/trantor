package dev.botta.trantor.core.auth.middlewares

import dev.botta.cqbus.ExecutionContext
import dev.botta.cqbus.Middleware
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.core.auth.UnauthorizedAccessError
import dev.botta.trantor.core.auth.requiredAuthorizationGrants

class GrantsAuthorizationMiddleware: Middleware {
    override fun <T: Request<R>, R> execute(request: T, next: (T) -> R, context: ExecutionContext): R {
        failIfNotAuthorized(request.javaClass, context)
        return next(request)
    }

    private fun failIfNotAuthorized(clazz: Class<Any>, context: ExecutionContext) {
        val requiredGrants = getRequiredGrants(clazz)
        if (requiredGrants.isEmpty()) return
        val identityGrants = getIdentityGrants(context)
        val hasARequiredGrant = identityGrants.any { requiredGrants.contains(it) }
        if (!hasARequiredGrant) throw UnauthorizedAccessError()
    }

    private fun getRequiredGrants(clazz: Class<Any>) = requiredAuthorizationGrants(clazz).map { it.lowercase() }

    private fun getIdentityGrants(context: ExecutionContext): List<String> {
        return context.identity.grants.map { it.lowercase() }
    }
}
