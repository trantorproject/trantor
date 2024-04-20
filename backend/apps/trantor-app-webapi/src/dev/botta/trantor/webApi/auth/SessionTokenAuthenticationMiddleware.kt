package dev.botta.trantor.webApi.auth

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import io.javalin.http.Context

class SessionTokenAuthenticationMiddleware: Middleware {
    override fun <T: Request<R>, R> execute(request: T, next: (T) -> R, context: ExecutionContext): R {
        extractSessionTokenFrom(context)
        return next(request)
    }

    private fun extractSessionTokenFrom(context: ExecutionContext) {
        val authorizationHeader = getAuthorizationHeader(context)
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) return
        val sessionToken = authorizationHeader.removePrefix("Bearer ")
        if (sessionToken.isBlank()) return
        context["SessionToken"] = sessionToken
    }

    private fun getAuthorizationHeader(context: ExecutionContext) = context.get<Context>()?.header("Authorization")
}
