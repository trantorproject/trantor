@file:Suppress("ClassName")

package dev.botta.trantor.web.application.requestmapper

import com.google.gson.JsonParseException
import dev.botta.cqbus.requests.Request
import dev.botta.json.values.JsonObject
import dev.botta.trantor.serialization.gson.GsonSerializer
import dev.botta.trantor.web.application.requestmapper.transformers.ApplicationRequestMapperJsonTransformer
import io.javalin.http.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

class ApplicationRequestMapperTest {
    @Nested
    inner class `from the body` {
        @Test
        fun `a json body becomes the request`() {
            val request = mapper.toRequest(PlaceOrder::class, contextOf(body = """{"customer":"nico","total":150}"""))

            assertThat(request.customer).isEqualTo("nico")
            assertThat(request.total).isEqualTo(150)
        }

        @Test
        fun `an empty body is an empty object, so a request of defaults still builds`() {
            val request = mapper.toRequest(PlaceOrder::class, contextOf(body = ""))

            assertThat(request.customer).isEmpty()
            assertThat(request.total).isZero()
        }

        @Test
        fun `a body that is not an object is ignored rather than crashing`() {
            val request = mapper.toRequest(PlaceOrder::class, contextOf(body = "\"just a string\""))

            assertThat(request.customer).isEmpty()
        }

        @Test
        fun `a body that is not json at all says so as a parse error`() {
            assertThatThrownBy { mapper.toRequest(PlaceOrder::class, contextOf(body = "{ not json")) }
                .isInstanceOf(Exception::class.java)
        }

        @Test
        fun `a field of the wrong type is a parse error, not a half built request`() {
            assertThatThrownBy {
                mapper.toRequest(PlaceOrder::class, contextOf(body = """{"total":"a lot"}"""))
            }.isInstanceOf(JsonParseException::class.java)
        }
    }

    @Nested
    inner class `from the query string` {
        @Test
        fun `a parameter becomes a field`() {
            val request = mapper.toRequest(PlaceOrder::class, contextOf(query = mapOf("customer" to listOf("nico"))))

            assertThat(request.customer).isEqualTo("nico")
        }

        @Test
        fun `a parameter is converted to whatever the field is`() {
            val request = mapper.toRequest(PlaceOrder::class, contextOf(query = mapOf("total" to listOf("150"))))

            assertThat(request.total).isEqualTo(150)
        }

        @Test
        fun `the literal null means null, not the text null`() {
            val request = mapper.toRequest(PlaceOrder::class, contextOf(query = mapOf("note" to listOf("null"))))

            assertThat(request.note).isNull()
        }

        @Test
        fun `a name ending in brackets is a list`() {
            val context = contextOf(query = mapOf("tags[]" to listOf("urgent", "gift")))

            val request = mapper.toRequest(PlaceOrder::class, context)

            assertThat(request.tags).containsExactly("urgent", "gift")
        }

        @Test
        fun `a list with one value is still a list`() {
            val request = mapper.toRequest(PlaceOrder::class, contextOf(query = mapOf("tags[]" to listOf("urgent"))))

            assertThat(request.tags).containsExactly("urgent")
        }

        @Test
        fun `a dotted name is a nested object`() {
            val context = contextOf(query = mapOf("address.city" to listOf("Bariloche")))

            val request = mapper.toRequest(PlaceOrder::class, context)

            assertThat(request.address?.city).isEqualTo("Bariloche")
        }

        @Test
        fun `two dotted names fill the same object`() {
            val context = contextOf(
                query = mapOf("address.city" to listOf("Bariloche"), "address.street" to listOf("Mitre")),
            )

            val request = mapper.toRequest(PlaceOrder::class, context)

            assertThat(request.address?.city).isEqualTo("Bariloche")
            assertThat(request.address?.street).isEqualTo("Mitre")
        }

        @Test
        fun `a dotted name adds to what the body already sent`() {
            val context = contextOf(
                body = """{"address":{"street":"Mitre"}}""",
                query = mapOf("address.city" to listOf("Bariloche")),
            )

            val request = mapper.toRequest(PlaceOrder::class, context)

            assertThat(request.address?.street).isEqualTo("Mitre")
            assertThat(request.address?.city).isEqualTo("Bariloche")
        }

        @Test
        fun `only one level of nesting is understood, and the rest is left as it came`() {
            // A name with two dots is not split: it stays a field called "a.b.c", which no request has
            val context = contextOf(query = mapOf("address.city.name" to listOf("Bariloche")))

            assertThat(mapper.toRequest(PlaceOrder::class, context).address).isNull()
        }

        @Test
        fun `a parameter overrides what the body sent for the same field`() {
            val context = contextOf(
                body = """{"customer":"from body"}""",
                query = mapOf("customer" to listOf("from query")),
            )

            assertThat(mapper.toRequest(PlaceOrder::class, context).customer).isEqualTo("from query")
        }
    }

    @Nested
    inner class `from the path` {
        @Test
        fun `a path parameter becomes a field`() {
            val context = contextOf(path = mapOf("customer" to "nico"))

            assertThat(mapper.toRequest(PlaceOrder::class, context).customer).isEqualTo("nico")
        }

        @Test
        fun `it wins over the body, because the url is what was routed`() {
            val context = contextOf(body = """{"customer":"from body"}""", path = mapOf("customer" to "from path"))

            assertThat(mapper.toRequest(PlaceOrder::class, context).customer).isEqualTo("from path")
        }

        @Test
        fun `and over the query string, which runs before it`() {
            val context = contextOf(
                query = mapOf("customer" to listOf("from query")),
                path = mapOf("customer" to "from path"),
            )

            assertThat(mapper.toRequest(PlaceOrder::class, context).customer).isEqualTo("from path")
        }
    }

    @Nested
    inner class `all three together` {
        @Test
        fun `each fills the fields the others did not`() {
            val context = contextOf(
                body = """{"total":150}""",
                query = mapOf("note" to listOf("gift")),
                path = mapOf("customer" to "nico"),
            )

            val request = mapper.toRequest(PlaceOrder::class, context)

            assertThat(request.customer).isEqualTo("nico")
            assertThat(request.total).isEqualTo(150)
            assertThat(request.note).isEqualTo("gift")
        }
    }

    @Nested
    inner class `another source` {
        @Test
        fun `can be added, because the mapping is a pipeline`() {
            mapper.addRequestJsonTransformer(FixedTransformer("customer", "from a header"))

            assertThat(mapper.toRequest(PlaceOrder::class, contextOf()).customer).isEqualTo("from a header")
        }

        @Test
        fun `runs after the ones that ship, so it has the last word`() {
            mapper.addRequestJsonTransformer(FixedTransformer("customer", "from a header"))
            val context = contextOf(path = mapOf("customer" to "from path"))

            assertThat(mapper.toRequest(PlaceOrder::class, context).customer).isEqualTo("from a header")
        }

        @Test
        fun `the same one twice is added once`() {
            val transformer = FixedTransformer("customer", "once")
            mapper.addRequestJsonTransformer(transformer)
            mapper.addRequestJsonTransformer(transformer)

            mapper.toRequest(PlaceOrder::class, contextOf())

            assertThat(transformer.calls).isEqualTo(1)
        }
    }

    @Nested
    inner class `writing the response` {
        @Test
        fun `is json, with the status the route declared`() {
            val context = contextOf()

            mapper.addResponse(context, OrderPlaced("order-7"), statusCode = 201)

            verify { context.contentType("application/json") }
            verify { context.status(201) }
            assertThat(writtenTo(context)).isEqualTo("""{"id":"order-7"}""")
        }

        @Test
        fun `defaults to 200`() {
            val context = contextOf()

            mapper.addResponse(context, OrderPlaced("order-7"))

            verify { context.status(200) }
        }

        @Test
        fun `a handler that returns nothing writes no body`() {
            val context = contextOf()

            mapper.addResponse(context, null, statusCode = 204)

            verify { context.status(204) }
            verify(exactly = 0) { context.result(any<String>()) }
        }
    }

    private fun contextOf(
        body: String = "",
        query: Map<String, List<String>> = emptyMap(),
        path: Map<String, String> = emptyMap(),
    ) = mockk<Context>(relaxed = true).also {
        every { it.body() } returns body
        every { it.queryParamMap() } returns query
        every { it.pathParamMap() } returns path
    }

    private fun writtenTo(context: Context): String {
        val written = slot<String>()
        verify { context.result(capture(written)) }

        return written.captured
    }

    class PlaceOrder(
        val customer: String = "",
        val total: Int = 0,
        val note: String? = null,
        val tags: List<String> = emptyList(),
        val address: Address? = null,
    ): Request<OrderPlaced>

    class Address(val city: String? = null, val street: String? = null)

    class OrderPlaced(val id: String)

    private class FixedTransformer(
        private val field: String,
        private val value: String,
    ): ApplicationRequestMapperJsonTransformer {
        var calls = 0

        override fun <T: Request<*>> transform(requestType: KClass<T>, context: Context, json: JsonObject) {
            calls++
            json[field] = value
        }
    }

    private val mapper = ApplicationRequestMapper(GsonSerializer())
}
