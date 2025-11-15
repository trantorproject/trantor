package dev.botta.trantor.webApi.auth

import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.trantor.appServices.AppModule
import io.ktor.server.routing.*

class SessionTokenFromHeadersMiddleware: Middleware {
    override suspend fun <T: Request<R>, R> execute(request: T, next: suspend (T) -> R, context: ExecutionContext): R {
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

    private fun getAuthorizationHeader(context: ExecutionContext) =
        (context["routing_context"] as? RoutingContext)?.call?.request?.headers?.get("Authorization")
}

fun AppModule.addSessionTokenFromHeaders() {
    registerMiddleware(SessionTokenFromHeadersMiddleware())
}
