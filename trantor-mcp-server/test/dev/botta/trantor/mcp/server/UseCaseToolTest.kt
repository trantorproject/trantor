@file:Suppress("ClassName")

package dev.botta.trantor.mcp.server

import dev.botta.cqbus.requests.Command
import dev.botta.cqbus.requests.PureCommand
import dev.botta.cqbus.requests.Query
import dev.botta.cqbus.requests.Request
import dev.botta.json.Json
import dev.botta.json.values.JsonObject
import dev.botta.trantor.ai.RunContext
import dev.botta.trantor.ai.mcp.McpProtocol
import dev.botta.trantor.core.auth.NotAuthenticatedError
import dev.botta.trantor.core.auth.UnauthorizedAccessError
import dev.botta.trantor.core.validation.FieldError
import dev.botta.trantor.core.validation.ValidationError
import dev.botta.trantor.domain.Id
import dev.botta.trantor.domain.errors.DomainError
import dev.botta.trantor.domain.errors.ForbiddenError
import dev.botta.trantor.primitives.serialization.Description
import dev.botta.trantor.primitives.serialization.JsonSerializer
import dev.botta.trantor.primitives.serialization.schemaOf
import dev.botta.trantor.serialization.gson.GsonSerializer
import dev.botta.trantor.serialization.gson.adapters.StringValueSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A use case of the application as a tool of an MCP endpoint, served without a server in the middle: the call runs the
 * use case with a fake [McpCall] instead of the executor of the application. McpServerTest runs one for real.
 */
class UseCaseToolTest {
    @Nested
    inner class `listing it` {
        @Test
        fun `gives the schema the serializer of the application reads for the request`() {
            val tool = listed().first { it["name"]?.asString() == "place_order" }

            assertThat(tool["inputSchema"]).isEqualTo(serializer.schemaOf<PlaceOrder>())
            assertThat(tool["description"]?.asString()).isEqualTo("Places an order for an account")
        }

        @Test
        fun `a query only reads and a command does not, unless the tool says otherwise`() {
            val readOnly = listed().associate { it["name"]?.asString() to it.path("annotations.readOnlyHint") }

            assertThat(readOnly["stock"]).isEqualTo(Json.TRUE)
            assertThat(readOnly["place_order"]).isNull()
            assertThat(readOnly["forget_order"]).isEqualTo(Json.TRUE)
        }
    }

    @Nested
    inner class `calling it` {
        @Test
        fun `reads the arguments as the serializer does and runs the use case through the call`() {
            val arguments = Json.obj("account" to ACCOUNT.toString(), "sku" to "ABC-1", "quantity" to 2)

            val result = call("place_order", arguments)

            assertThat(ran).containsExactly(PlaceOrder(AccountId(ACCOUNT), Sku("ABC-1"), 2))
            assertThat(result["structuredContent"]).isEqualTo(Json.obj("order" to "A-1", "quantity" to 2))
        }

        @Test
        fun `a use case that answers a list gives it under result, since structured content is always an object`() {
            val result = call("orders", Json.obj())

            assertThat(result["structuredContent"]).isEqualTo(
                Json.obj("result" to Json.array(Json.obj("order" to "A-1", "quantity" to 2))),
            )
            assertThat(textOf(result)).isEqualTo("""{"result":[{"order":"A-1","quantity":2}]}""")
        }

        @Test
        fun `a use case that answers nothing says it was done`() {
            val result = call("forget_order", Json.obj("order" to "A-1"))

            assertThat(result["content"]).isEqualTo(Json.array(Json.obj("type" to "text", "text" to "Done.")))
        }

        @Test
        fun `arguments the serializer cannot read are an error the model can fix`() {
            val result = call("place_order", Json.obj("account" to "123", "sku" to "ABC-1"))

            assertThat(ran).isEmpty()
            assertThat(result["isError"]?.asBoolean()).isTrue()
            assertThat(textOf(result)).contains("'123'")
        }
    }

    @Nested
    inner class `a use case that fails` {
        @Test
        fun `with an error of the domain, of the validation or of a permission, fails as the tool`() {
            val errors = listOf(
                DomainError("There is no stock of ABC-1"),
                ForbiddenError("The account is closed"),
                ValidationError(listOf(FieldError("quantity", "must be at least 1"))),
                UnauthorizedAccessError("Only sellers place orders"),
            )

            val results = errors.map { error -> call("stock", Json.obj("sku" to "ABC-1")) { throw error } }

            assertThat(results.map { it["isError"]?.asBoolean() }).containsOnly(true)
            assertThat(results.map { textOf(it) }).containsExactly(
                "There is no stock of ABC-1",
                "The account is closed",
                "Validation failed: quantity: must be at least 1",
                "Only sellers place orders",
            )
        }

        @Test
        fun `because nobody said who asks is a 401, which is what makes a client ask for credentials`() {
            val answer = post("stock", Json.obj("sku" to "ABC-1")) { throw NotAuthenticatedError() }

            assertThat(answer.status).isEqualTo(401)
            assertThat(answer.headers).containsEntry("WWW-Authenticate", "Bearer")
        }
    }

    @Nested
    inner class `declaring it` {
        @Test
        fun `on routes that cannot run a use case fails, saying where it has to be`() {
            assertThatThrownBy { McpEndpointBuilder("store", "1.0.0", null).tool<GetStock>("stock", "The stock") }
                .isInstanceOf(McpServerError::class.java)
                .hasMessageContaining("ApplicationController")
        }

        @Test
        fun `with a serializer that cannot tell the schema of what it reads fails`() {
            val serializer = object: JsonSerializer by GsonSerializer() {}

            assertThatThrownBy { McpEndpointBuilder("store", "1.0.0", serializer).tool<GetStock>("stock", "The stock") }
                .isInstanceOf(McpServerError::class.java)
                .hasMessageContaining("JsonSchemaSource")
        }
    }

    private fun listed() = post("tools/list", null).let { result(it) }["tools"]?.asArray()!!.map { it.asObject()!! }

    private fun call(tool: String, arguments: JsonObject, run: (Request<*>) -> Any? = ::answer) =
        result(post(tool, arguments, run))

    private fun post(tool: String, arguments: JsonObject?, run: (Request<*>) -> Any? = ::answer): McpHttpResponse {
        val method = if (arguments == null) "tools/list" else "tools/call"
        val params = Json.obj(
            "_meta" to Json.obj(McpProtocol.Meta.PROTOCOL_VERSION to McpProtocol.VERSION),
        )
        if (arguments != null) {
            params["name"] = tool
            params["arguments"] = arguments
        }
        val headers = buildMap {
            put(McpProtocol.Headers.PROTOCOL_VERSION, McpProtocol.VERSION)
            put(McpProtocol.Headers.METHOD, method)
            if (arguments != null) put(McpProtocol.Headers.NAME, tool)
        }
        val body = Json.obj("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params)

        return endpoint.handle(McpHttpRequest("POST", body.toString(), headers), RunContext(McpCall(run)))
    }

    private fun answer(request: Request<*>): Any? {
        ran += request
        return when (request) {
            is PlaceOrder -> OrderPlaced("A-1", request.quantity)
            is GetStock -> Stock(request.sku, 12)
            is ListOrders -> listOf(OrderPlaced("A-1", 2))
            else -> Unit
        }
    }

    private fun result(answer: McpHttpResponse) =
        Json.parse(answer.body!!).asObject()!!["result"]?.asObject() ?: error("No result: ${answer.body}")

    private fun textOf(result: JsonObject) =
        result.path("content")?.asArray()?.first()?.asObject()?.get("text")?.asString()

    private val ran = mutableListOf<Request<*>>()

    private val serializer = GsonSerializer().apply {
        registerTypeAdapter(Sku::class.java, StringValueSerializer({ Sku(it) }, { it.value }))
    }

    private val endpoint = McpEndpointBuilder("store", "1.0.0", serializer)
        .tool<PlaceOrder>("place_order", "Places an order for an account")
        .tool<GetStock>("stock", "The stock of a product")
        .tool<ForgetOrder>("forget_order", "Forgets an order", readOnly = true)
        .tool<ListOrders>("orders", "The orders placed")
        .build()

    class AccountId(raw: UUID): Id(raw)

    data class Sku(val value: String)

    data class PlaceOrder(
        val account: AccountId,
        val sku: Sku,
        @Description("How many units") val quantity: Int = 1,
    ): Command<OrderPlaced>

    data class OrderPlaced(val order: String, val quantity: Int)

    data class GetStock(val sku: Sku): Query<Stock>

    data class Stock(val sku: Sku, val units: Int)

    data class ForgetOrder(val order: String): PureCommand

    class ListOrders: Query<List<OrderPlaced>>

    private companion object {
        val ACCOUNT: UUID = UUID(0, 1)
    }
}
