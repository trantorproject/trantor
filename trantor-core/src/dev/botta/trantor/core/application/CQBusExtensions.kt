package dev.botta.trantor.core.application

import dev.botta.cqbus.CQBus
import dev.botta.cqbus.requests.Request
import dev.botta.cqbus.requests.handlers.ContextAwareRequestHandler
import dev.botta.cqbus.requests.handlers.RequestHandler
import kotlin.reflect.KClass
import kotlin.reflect.full.allSupertypes

/**
 * Registers [handlerType] as the handler of the request its declaration names: a
 * `PlaceOrderHandler: RequestHandler<PlaceOrder, Order>` handles `PlaceOrder`, and a [ContextAwareRequestHandler]
 * is registered as one. [factory] is called for every request, as the bus does with any handler.
 *
 * Throws [IllegalArgumentException] when [handlerType] is not a handler, or when its request is a type parameter
 * (`class AuditHandler<T: Request<Unit>>`), since then nothing says which request it is: register an instance of it
 * instead, whose type says it.
 */
@Suppress("UNCHECKED_CAST")
fun <H: Any> CQBus.addHandler(handlerType: KClass<H>, factory: () -> H) {
    val name = handlerType.qualifiedName ?: handlerType.java.name
    val handlerInterface = handlerType.allSupertypes.firstOrNull { it.classifier in HANDLER_INTERFACES }
        ?: throw IllegalArgumentException("$name is neither a RequestHandler nor a ContextAwareRequestHandler")
    val requestType = handlerInterface.arguments.first().type?.classifier as? KClass<*>
        ?: throw IllegalArgumentException(
            "$name does not say which request it handles, its request is a type parameter. " +
                "Register an instance of it, or a subclass that names the request.",
        )

    val requestClass = requestType.java as Class<Request<Any?>>
    if (handlerInterface.classifier == RequestHandler::class) {
        registerHandler(requestClass) { factory() as RequestHandler<Request<Any?>, Any?> }
    } else {
        registerContextAwareHandler(requestClass) { factory() as ContextAwareRequestHandler<Request<Any?>, Any?> }
    }
}

private val HANDLER_INTERFACES = setOf(RequestHandler::class, ContextAwareRequestHandler::class)
