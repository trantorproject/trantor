package dev.botta.trantor.core.auth

import dev.botta.cqbus.identity.Identity
import dev.botta.cqbus.requests.Query

/**
 * Who is asking, as the middlewares of the application tell it. It answers the identity of its execution context,
 * so running it with the context of a request, or of an HTTP call, gives who that request runs as. An application
 * registers the handler with its bus when it builds; `ApplicationExecutor.identityOf` runs it.
 */
object CurrentIdentity: Query<Identity>
