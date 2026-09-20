@file:Suppress("ClassName")

package dev.botta.trantor.ai.models.middleware

import dev.botta.trantor.ai.models.CallOptions
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.testing.FakeChatModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChatModelMiddlewareTest {
    @Test
    fun `a model without middlewares is itself`() {
        assertThat(model.with(emptyList())).isSameAs(model)
    }

    @Test
    fun `keeps saying which model it is`() {
        val wrapped = FakeChatModel(modelId = "gpt-4.1-mini", provider = "openai").with(Recorder("a"))

        assertThat(wrapped.modelId).isEqualTo("gpt-4.1-mini")
        assertThat(wrapped.provider).isEqualTo("openai")
    }

    @Test
    fun `the first middleware is the outermost`() {
        model.with(Recorder("a"), Recorder("b")).generate(ChatRequest("Hola"))

        assertThat(calls).containsExactly("a in", "b in", "b out", "a out")
    }

    @Test
    fun `the same order applies while streaming`() {
        model.with(Recorder("a"), Recorder("b")).stream(ChatRequest("Hola"))

        assertThat(calls).containsExactly("a in", "b in", "b out", "a out")
    }

    @Test
    fun `a middleware that does not call next answers on its own`() {
        val wrapped = model.with(ShortCircuit, Recorder("never"))

        val response = wrapped.generate(ChatRequest("Hola"))

        assertThat(response.text).isEqualTo("from the cache")
        assertThat(calls).isEmpty()
        assertThat(model.request).isNull()
    }

    @Test
    fun `transforms run before the call, in order`() {
        val wrapped = model.with(AddSystem("primero"), AddSystem("segundo"))

        wrapped.generate(ChatRequest("Hola"))

        assertThat(model.request?.messages?.filterIsInstance<Message.System>()?.map { it.text })
            .containsExactly("primero", "segundo")
    }

    @Test
    fun `what a transform changed is what the model gets`() {
        val wrapped = model.with(object: ChatModelMiddleware {
            override fun transform(request: ChatRequest, model: ChatModel) =
                request.copy(settings = ChatSettings(temperature = 0.1))
        })

        wrapped.generate(ChatRequest("Hola"))

        assertThat(model.request?.settings?.temperature).isEqualTo(0.1)
    }

    @Test
    fun `a middleware sees the request the ones before it left`() {
        val seen = mutableListOf<Int>()
        val wrapped = model.with(
            AddSystem("uno"),
            object: ChatModelMiddleware {
                override fun generate(
                    request: ChatRequest,
                    options: CallOptions,
                    next: (ChatRequest, CallOptions) -> ChatResponse,
                ) = next(request, options).also { seen.add(request.messages.size) }
            },
        )

        wrapped.generate(ChatRequest("Hola"))

        assertThat(seen).containsExactly(2)
    }

    private inner class Recorder(private val name: String): ChatModelMiddleware {
        override fun generate(
            request: ChatRequest,
            options: CallOptions,
            next: (ChatRequest, CallOptions) -> ChatResponse,
        ): ChatResponse {
            calls.add("$name in")
            return next(request, options).also { calls.add("$name out") }
        }

        override fun stream(
            request: ChatRequest,
            options: CallOptions,
            next: (ChatRequest, CallOptions) -> ChatStream,
        ): ChatStream {
            calls.add("$name in")
            return next(request, options).also { calls.add("$name out") }
        }
    }

    private class AddSystem(private val text: String): ChatModelMiddleware {
        override fun transform(request: ChatRequest, model: ChatModel) =
            request.copy(messages = request.messages + Message.system(text))
    }

    private object ShortCircuit: ChatModelMiddleware {
        override fun generate(
            request: ChatRequest,
            options: CallOptions,
            next: (ChatRequest, CallOptions) -> ChatResponse,
        ) = FakeChatModel().generate(ChatRequest("x")).copy(content = listOf(TextPart("from the cache")))
    }

    private val calls = mutableListOf<String>()
    private val model = FakeChatModel()
}
