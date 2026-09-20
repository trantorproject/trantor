@file:Suppress("ClassName")

package dev.botta.trantor.web.server

import dev.botta.trantor.web.errorHandlers.NotFoundErrorHandler
import dev.botta.trantor.web.server.controllers.Controller
import io.restassured.RestAssured
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket

@Tag("slow")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpServerTest {
    @Nested
    inner class `routing` {
        @Test
        fun `answers a get on the path it was given`() {
            RestAssured.given().get("/ping").then().statusCode(200).body("message", equalTo("pong"))
        }

        @Test
        fun `answers a post`() {
            RestAssured.given().body("""{"name":"nico"}""").post("/greet")
                .then().statusCode(200).body("message", equalTo("hola nico"))
        }

        @Test
        fun `reads a path parameter`() {
            RestAssured.given().get("/orders/order-7").then().statusCode(200).body("id", equalTo("order-7"))
        }

        @Test
        fun `reads a query parameter`() {
            RestAssured.given().get("/search?q=libros").then().statusCode(200).body("query", equalTo("libros"))
        }

        @Test
        fun `a path nobody registered is a 404`() {
            RestAssured.given().get("/nothing-here").then().statusCode(404)
        }

        @Test
        fun `the wrong method on a known path is not a match either`() {
            RestAssured.given().delete("/ping").then().statusCode(404)
        }
    }

    @Nested
    inner class `a controller` {
        @Test
        fun `registers all of its routes`() {
            RestAssured.given().get("/invoices").then().statusCode(200)
            RestAssured.given().get("/invoices/inv-1").then().statusCode(200).body("id", equalTo("inv-1"))
        }
    }

    @Nested
    inner class `error handlers` {
        @Test
        fun `turn the exception they were registered for into their status`() {
            RestAssured.given().get("/orders/missing/load")
                .then().statusCode(404)
                .body("type", equalTo("OrderNotFound"))
                .body("message", equalTo("No such order"))
        }

        @Test
        fun `an exception nobody registered is a 500`() {
            RestAssured.given().get("/boom").then().statusCode(500)
        }
    }

    @Nested
    inner class `correlation ids` {
        @Test
        fun `every response carries one, so a log line can be traced back`() {
            val id = RestAssured.given().get("/ping").then().extract().header("X-Request-Id")

            assertThat(id).isNotBlank()
        }

        @Test
        fun `the one the client sent is kept, so a trace crosses services`() {
            RestAssured.given().header("X-Request-Id", "abc123").get("/ping")
                .then().header("X-Request-Id", equalTo("abc123"))
        }

        @Test
        fun `an empty one is replaced instead of passed on`() {
            val id = RestAssured.given().header("X-Request-Id", "").get("/ping")
                .then().extract().header("X-Request-Id")

            assertThat(id).isNotBlank()
        }
    }

    @Nested
    inner class `filters` {
        @Test
        fun `a before filter runs on every request`() {
            RestAssured.given().get("/ping").then().header("X-Served-By", equalTo("trantor"))
            RestAssured.given().get("/search?q=x").then().header("X-Served-By", equalTo("trantor"))
        }
    }

    @BeforeAll
    fun startTheServer() {
        port = freePort()
        server = HttpServer(HttpServerSettings(port = port))

        server.before { it.header("X-Served-By", "trantor") }
        server.get("/ping") { it.jsonObj("message" to "pong") }
        server.post("/greet") { it.jsonObj("message" to "hola ${it.jsonBody()["name"]?.asString()}") }
        server.get("/orders/{id}") { it.jsonObj("id" to it.pathParam("id")) }
        server.get("/search") { it.jsonObj("query" to it.queryParam("q")) }
        server.get("/orders/{id}/load") { throw OrderNotFound("No such order") }
        server.get("/boom") { error("something nobody planned for") }
        server.addController(InvoicesController())
        server.addErrorHandler(NotFoundErrorHandler(OrderNotFound::class))

        server.start()
        RestAssured.baseURI = "http://localhost:$port"
    }

    @AfterAll
    fun stopTheServer() {
        server.stop(10)
        RestAssured.reset()
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private class InvoicesController: Controller {
        override fun registerRoutes(http: RouteRegister) {
            http.get("/invoices") { it.jsonObj("invoices" to 0) }
            http.get("/invoices/{id}") { it.jsonObj("id" to it.pathParam("id")) }
        }
    }

    private class OrderNotFound(message: String): Exception(message)

    private var port = 0
    private lateinit var server: HttpServer
}
