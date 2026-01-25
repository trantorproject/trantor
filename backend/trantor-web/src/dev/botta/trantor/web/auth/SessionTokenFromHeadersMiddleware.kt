package dev.botta.trantor.web.auth

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.web.application.WebApplication
import io.javalin.http.Context

class SessionTokenFromHeadersMiddleware: Middleware {
    override fun <T: Request<R>, R> execute(request: T, next: (T) -> R, context: ExecutionContext): R {
        extractSessionTokenFrom(context)
        return next(request)
    }

    private fun extractSessionTokenFrom(context: ExecutionContext) {
        val authorizationHeader = getAuthorizationHeader(context)
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) return
        val sessionToken = authorizationHeader.removePrefix("Bearer ")
        if (sessionToken.isBlank()) return
        context["session_token"] = sessionToken
    }

    private fun getAuthorizationHeader(context: ExecutionContext) = (context["javalin_context"] as? Context)?.header("Authorization")
}

fun WebApplication.addSessionTokenFromHeaders() {
    registerMiddleware(SessionTokenFromHeadersMiddleware())
}
