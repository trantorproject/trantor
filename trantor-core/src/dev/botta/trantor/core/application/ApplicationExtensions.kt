package dev.botta.trantor.core.application

import dev.botta.cqbus.CQBus
import dev.botta.cqbus.requests.Request
import dev.botta.cqbus.requests.handlers.ContextAwareRequestHandler
import dev.botta.cqbus.requests.handlers.RequestHandler

/**
 * Registers [H] as the handler of the request its declaration names, `RequestHandler<PlaceOrder, Order>` or
 * `ContextAwareRequestHandler<PlaceOrder, Order>`. The container builds one for every request it handles, so its
 * constructor gets what it depends on, the scoped services of that request included.
 *
 * Fails, when it is called, for a class that is not a handler or whose request is a type parameter.
 */
inline fun <reified H: Any> Application.addHandler() {
    services.get<CQBus>().addHandler(H::class) { services.create<H>() }
}

/** Registers [handler] for the requests of type [T]. The same instance handles all of them. */
inline fun <reified T: Request<R>, R> Application.addHandler(handler: RequestHandler<T, R>) {
    services.get<CQBus>().registerHandler(T::class.java) { handler }
}

/** Registers [handler] for the requests of type [T]. The same instance handles all of them. */
inline fun <reified T: Request<R>, R> Application.addHandler(handler: ContextAwareRequestHandler<T, R>) {
    services.get<CQBus>().registerContextAwareHandler(T::class.java) { handler }
}
