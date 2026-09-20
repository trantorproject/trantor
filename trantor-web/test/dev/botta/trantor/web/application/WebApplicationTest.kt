@file:Suppress("ClassName")

package dev.botta.trantor.web.application

import dev.botta.cqbus.CQBus
import dev.botta.cqbus.requests.Request
import dev.botta.cqbus.requests.handlers.ContextAwareRequestHandler
import dev.botta.cqbus.requests.handlers.RequestHandler
import dev.botta.trantor.config.providers.addMemoryCollection
import dev.botta.trantor.domain.errors.DomainError
import dev.botta.trantor.domain.errors.NotFoundError
import dev.botta.trantor.web.application.routes.ApplicationRouteRegister
import dev.botta.trantor.web.auth.addSessionTokenFromHeaders
import io.restassured.RestAssured
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.*
import java.net.ServerSocket

/**
 * The feature that makes trantor-web more than a Javalin wrapper: a route declared as the use case it
 * runs, with the request built out of the http call and the answer serialized back.
 */
@Tag("slow")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebApplicationTest {
    @Nested
    inner class `a route that is a use case` {
        @Test
        fun `builds the request out of the body and answers with what the handler returned`() {
            RestAssured.given().body("""{"customer":"nico","quantity":2}""").post("/orders")
                .then().statusCode(201).body("customer", equalTo("nico")).body("quantity", equalTo(2))
        }

        @Test
        fun `a field the body left out takes the default of the request class`() {
            RestAssured.given().body("""{"customer":"nico"}""").post("/orders")
                .then().statusCode(201).body("quantity", equalTo(0))
        }

        @Test
        fun `answers 200 when the route did not say otherwise`() {
            RestAssured.given().get("/orders/order-7").then().statusCode(200)
        }

        @Test
        fun `builds the request out of the path`() {
            RestAssured.given().get("/orders/order-7").then().body("id", equalTo("order-7"))
        }

        @Test
        fun `builds it out of the query string too`() {
            RestAssured.given().get("/orders?customer=nico").then().body("customer", equalTo("nico"))
        }

        @Test
        fun `works for put, patch and delete as well`() {
            RestAssured.given().body("""{"quantity":5}""").put("/orders/order-7").then().statusCode(200)
            RestAssured.given().body("""{"quantity":5}""").patch("/orders/order-7").then().statusCode(200)
            RestAssured.given().delete("/orders/order-7").then().statusCode(204)
        }
    }

    @Nested
    inner class `the errors it knows about` {
        @Test
        fun `a domain error the application did not name is a 400`() {
            RestAssured.given().body("""{"customer":"","quantity":1}""").post("/orders")
                .then().statusCode(400).body("type", equalTo("OrderRejected"))
        }

        @Test
        fun `a not found error is a 404`() {
            RestAssured.given().get("/orders/missing").then().statusCode(404)
        }

        @Test
        fun `a body that is not json is a 400, not a 500`() {
            RestAssured.given().body("{ not json").post("/orders").then().statusCode(400)
        }

        @Test
        fun `anything else is a 500 that does not leak the message`() {
            RestAssured.given().get("/boom")
                .then().statusCode(500)
                .body("type", equalTo("Exception"))
                .body("message", equalTo("Internal error"))
        }
    }

    @Nested
    inner class `a controller` {
        @Test
        fun `registers routes that are use cases`() {
            RestAssured.given().get("/invoices/inv-1").then().statusCode(200).body("id", equalTo("inv-1"))
        }
    }

    @Nested
    inner class `the execution context` {
        @Test
        fun `carries the javalin context, which is how a middleware reaches the headers`() {
            RestAssured.given().header("Authorization", "Bearer abc123").get("/whoami")
                .then().body("token", equalTo("abc123"))
        }
    }

    @BeforeAll
    fun startTheApplication() {
        val port = ServerSocket(0).use { it.localPort }

        val builder = WebApplication.builder { appName = "test"; environmentName = "DEVELOPMENT" }
        builder.config.addMemoryCollection("httpServer.port" to port.toString())
        app = builder.build()

        val bus = app.services.get<CQBus>()
        bus.registerHandler<PlaceOrder, OrderView> {
            RequestHandler { request, _ ->
                if (request.customer.isBlank()) throw OrderRejected("A customer is required")
                OrderView("order-1", request.customer, request.quantity)
            }
        }
        bus.registerHandler<GetOrder, OrderView> {
            RequestHandler { request, _ ->
                if (request.id == "missing") throw NotFoundError("No such order")
                OrderView(request.id, "nico", 1)
            }
        }
        bus.registerHandler<UpdateOrder, OrderView> {
            RequestHandler { request, _ -> OrderView(request.id, "nico", request.quantity) }
        }
        bus.registerHandler<CancelOrder, Unit> { RequestHandler { _, _ -> } }
        bus.registerHandler<GetInvoice, InvoiceView> { RequestHandler { request, _ -> InvoiceView(request.id) } }
        bus.registerHandler<Boom, Unit> { RequestHandler { _, _ -> error("something nobody planned for") } }
        bus.registerContextAwareHandler<WhoAmI, TokenView> {
            ContextAwareRequestHandler { _, context -> TokenView(context["session_token"] as? String) }
        }

        app.addSessionTokenFromHeaders()
        app.routes.post<PlaceOrder>("/orders", statusCode = 201)
        app.routes.get<GetOrder>("/orders/{id}")
        app.routes.get<PlaceOrder>("/orders")
        app.routes.put<UpdateOrder>("/orders/{id}")
        app.routes.patch<UpdateOrder>("/orders/{id}")
        app.routes.delete<CancelOrder>("/orders/{id}", statusCode = 204)
        app.routes.get<Boom>("/boom")
        app.routes.get<WhoAmI>("/whoami")
        app.addController(InvoicesController())

        app.start()
        RestAssured.baseURI = "http://localhost:$port"
    }

    @AfterAll
    fun stopTheApplication() {
        app.stop(10)
        RestAssured.reset()
    }

    class PlaceOrder(val customer: String = "", val quantity: Int = 0): Request<OrderView>

    class GetOrder(val id: String = ""): Request<OrderView>

    class UpdateOrder(val id: String = "", val quantity: Int = 0): Request<OrderView>

    class CancelOrder(val id: String = ""): Request<Unit>

    class GetInvoice(val id: String = ""): Request<InvoiceView>

    class Boom: Request<Unit>

    class WhoAmI: Request<TokenView>

    class OrderView(val id: String, val customer: String, val quantity: Int)

    class InvoiceView(val id: String)

    class TokenView(val token: String?)

    class OrderRejected(message: String): DomainError(message)

    class InvoicesController: ApplicationController {
        override fun registerRoutes(http: ApplicationRouteRegister) {
            http.get<GetInvoice>("/invoices/{id}")
        }
    }

    private lateinit var app: WebApplication
}
