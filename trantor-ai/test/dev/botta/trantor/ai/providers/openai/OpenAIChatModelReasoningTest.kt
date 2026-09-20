@file:Suppress("ClassName")

package dev.botta.trantor.ai.providers.openai

import dev.botta.json.Json
import dev.botta.trantor.ai.models.chat.*
import dev.botta.trantor.ai.providers.ProviderMetadata
import dev.botta.trantor.ai.testing.FakeHttpClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class OpenAIChatModelReasoningTest {
    @Nested
    inner class `what it asks for` {
        @Test
        fun `sends the effort`() {
            httpClient.body = fixture("text-with-reasoning-item.json")

            generateWith(Reasoning.effort(ReasoningEfforts.High))

            assertThat(sentBody()["reasoning"].toString()).isEqualTo("""{"effort":"high"}""")
        }

        @Test
        fun `sends the summary it wants`() {
            httpClient.body = fixture("reasoning-with-summary.json")

            generateWith(Reasoning.effort(ReasoningEfforts.Low, ReasoningSummaries.Detailed))

            assertThat(sentBody()["reasoning"].toString()).isEqualTo("""{"effort":"low","summary":"detailed"}""")
        }

        @Test
        fun `asks for the encrypted reasoning, which is what lets the next turn go on`() {
            httpClient.body = fixture("text-with-reasoning-item.json")

            generateWith(Reasoning.effort(ReasoningEfforts.Low))

            assertThat(sentBody()["include"]?.asArray()?.map { it.asString() })
                .containsExactly("reasoning.encrypted_content")
        }

        @Test
        fun `a request without reasoning does not ask for any`() {
            httpClient.body = fixture("text-simple.json")

            model.generate(ChatRequest(Message.user("Hola")))

            assertThat(sentBody().containsKey("reasoning")).isFalse()
            assertThat(sentBody().containsKey("include")).isFalse()
        }

        @Test
        fun `reasoning off asks for nothing, like not setting it`() {
            httpClient.body = fixture("text-simple.json")

            generateWith(Reasoning.Off)

            assertThat(sentBody().containsKey("reasoning")).isFalse()
        }

        @Test
        fun `a token budget is not something OpenAI takes, and says so`() {
            httpClient.body = fixture("text-simple.json")

            val response = generateWith(Reasoning.budget(4096))

            assertThat(sentBody().containsKey("reasoning")).isFalse()
            assertThat(response.warnings.map { it.setting }).containsExactly("reasoning.budgetTokens")
        }

        @Test
        fun `a budget with a summary still asks for the summary`() {
            httpClient.body = fixture("reasoning-with-summary.json")

            generateWith(Reasoning.budget(4096, ReasoningSummaries.Auto))

            assertThat(sentBody()["reasoning"].toString()).isEqualTo("""{"summary":"auto"}""")
        }

        @Test
        fun `does not say anything about storing unless the app asked`() {
            httpClient.body = fixture("text-simple.json")

            model.generate(ChatRequest(Message.user("Hola")))

            assertThat(sentBody().containsKey("store")).isFalse()
        }

        @Test
        fun `an app that does not want OpenAI to keep the call says so`() {
            httpClient.body = fixture("text-simple.json")
            val model = OpenAIChatModel("o4-mini", OpenAIConfig(apiKey = "sk-test", store = false), httpClient)

            model.generate(ChatRequest(Message.user("Hola")))

            assertThat(sentBody()["store"]?.asBoolean()).isFalse()
        }
    }

    @Nested
    inner class `what it reads` {
        @Test
        fun `reads the reasoning item as a reasoning part`() {
            httpClient.body = fixture("text-with-reasoning-item.json")

            val response = generateWith(Reasoning.effort(ReasoningEfforts.Low))

            val reasoning = response.content.filterIsInstance<ReasoningPart>().single()
            assertThat(reasoning.opaque?.get("encrypted_content")?.asString()).startsWith("gAAAAAB")
            assertThat(reasoning.metadata["openai"])
                .isEqualTo(Json.obj("id" to "rs_045bfa4f141af68a006aaf32baf9b887d289cd9c3b903e7e0e"))
            assertThat(response.text).isEqualTo("6 por 7 es 42.")
        }

        @Test
        fun `there is no summary unless it was asked for`() {
            httpClient.body = fixture("text-with-reasoning-item.json")

            val response = generateWith(Reasoning.effort(ReasoningEfforts.Low))

            assertThat(response.content.filterIsInstance<ReasoningPart>().single().text).isNull()
        }

        @Test
        fun `reads the summary`() {
            httpClient.body = fixture("reasoning-with-summary.json")

            val response = generateWith(Reasoning.effort(ReasoningEfforts.Medium, ReasoningSummaries.Auto))

            assertThat(response.content.filterIsInstance<ReasoningPart>().single().text)
                .startsWith("**Calculating apple distribution**")
        }

        @Test
        fun `a summary can come back empty even when it was asked for`() {
            httpClient.body = fixture("reasoning-empty-summary.json")

            val response = generateWith(Reasoning.effort(ReasoningEfforts.Medium, ReasoningSummaries.Auto))

            assertThat(response.content.filterIsInstance<ReasoningPart>().single().text).isNull()
        }

        // Long reasoning comes back as several blocks; none of the recordings has more than one
        @Test
        fun `joins the blocks of the summary`() {
            httpClient.body = twoSummaryBlocks

            val response = generateWith(Reasoning.effort(ReasoningEfforts.High, ReasoningSummaries.Detailed))

            assertThat(response.content.filterIsInstance<ReasoningPart>().single().text)
                .isEqualTo("Primero pense esto.\n\nDespues esto otro.")
        }

        @Test
        fun `counts the reasoning tokens as part of the output`() {
            httpClient.body = fixture("reasoning-with-summary.json")

            val usage = generateWith(Reasoning.effort(ReasoningEfforts.Medium)).usage

            assertThat(usage.outputTokens).isEqualTo(215)
            assertThat(usage.reasoningTokens).isEqualTo(128)
            assertThat(usage.totalTokens).isEqualTo(339)
        }

        @Test
        fun `an output item it does not model is still kept whole`() {
            httpClient.body = fixture("unknown-item.json")

            val response = model.generate(ChatRequest(Message.user("Que temperatura hay?")))

            val kept = response.content.filterIsInstance<ProviderPart>().single()
            assertThat(kept.provider).isEqualTo("openai")
            assertThat(kept.type).isEqualTo("web_search_call")
            assertThat(kept.raw.path("action.query")?.asString()).isEqualTo("clima Bariloche")
        }
    }

    @Nested
    inner class `what it sends back` {
        @Test
        fun `sends the reasoning back exactly as it came`() {
            httpClient.body = fixture("text-with-reasoning-item.json")
            val answer = generateWith(Reasoning.effort(ReasoningEfforts.Low)).content

            model.generate(ChatRequest(Message.user("Cuanto es 6 por 7?"), Message.Assistant(answer)))

            val sent = sentBody()["input"]?.asArray()?.get(1)
            assertThat(sent).isEqualTo(fixtureJson("text-with-reasoning-item.json")["output"]?.asArray()?.get(0))
        }

        @Test
        fun `the reasoning goes before the message it belongs to`() {
            httpClient.body = fixture("reasoning-with-summary.json")
            val answer = generateWith(Reasoning.effort(ReasoningEfforts.High)).content

            model.generate(ChatRequest(Message.user("Cuanto es 6 por 7?"), Message.Assistant(answer)))

            val types = sentBody()["input"]?.asArray()?.map { it.asObject()?.get("type")?.asString() }
            assertThat(types).containsExactly("message", "reasoning", "message")
        }

        @Test
        fun `reasoning of another provider is dropped with a warning`() {
            httpClient.body = fixture("text-simple.json")
            val foreign = ReasoningPart(
                text = "pensando",
                opaque = Json.obj("signature" to "abc"),
                metadata = ProviderMetadata.of("anthropic", Json.obj("id" to "th_1")),
            )

            val response = model.generate(ChatRequest(Message.user("Hola"), Message.Assistant(listOf(foreign))))

            assertThat(sentBody()["input"]?.asArray()?.size).isEqualTo(1)
            assertThat(response.warnings.map { it.message })
                .containsExactly("Reasoning that OpenAI did not produce was dropped")
        }
    }

    @Nested
    inner class `while it streams` {
        @Test
        fun `returns the summary deltas as they arrive`() {
            httpClient.body = fixture("stream-reasoning.txt")

            val deltas = model.stream(ChatRequest(Message.user("Cuanto es 6 por 7?"))).use { stream ->
                stream.asSequence().filterIsInstance<StreamPart.ReasoningDelta>().map { it.text }.toList()
            }

            assertThat(deltas).containsExactly("Pensando ", "en voz alta.")
        }

        @Test
        fun `the finished reasoning item comes out as a part`() {
            httpClient.body = fixture("stream-reasoning.txt")

            val parts = model.stream(ChatRequest(Message.user("Cuanto es 6 por 7?"))).use { stream ->
                stream.asSequence().filterIsInstance<StreamPart.PartDone>().map { it.part }.toList()
            }

            val reasoning = parts.filterIsInstance<ReasoningPart>().single()
            assertThat(reasoning.text).isEqualTo("Pensando en voz alta.")
            assertThat(reasoning.opaque?.get("encrypted_content")?.asString()).isEqualTo("gAAAAABqrzK7STREAM")
        }

        @Test
        fun `the whole response carries the reasoning and its tokens`() {
            httpClient.body = fixture("stream-reasoning.txt")

            val response = model.stream(ChatRequest(Message.user("Cuanto es 6 por 7?"))).use { it.response() }

            assertThat(response.content.filterIsInstance<ReasoningPart>()).hasSize(1)
            assertThat(response.text).isEqualTo("42.")
            assertThat(response.usage.reasoningTokens).isEqualTo(192)
        }
    }

    private fun generateWith(reasoning: Reasoning) = model.generate(
        ChatRequest(listOf(Message.user("Cuanto es 6 por 7?")), settings = ChatSettings(reasoning = reasoning))
    )

    private fun sentBody() = Json.parse(httpClient.requestBody!!).asObject()!!

    private fun fixtureJson(name: String) = Json.parse(fixture(name)).asObject()!!

    private fun fixture(name: String) =
        javaClass.getResource("/openai/$name")?.readText() ?: error("Missing fixture $name")

    private val twoSummaryBlocks = """
        {
          "id": "resp_1", "status": "completed", "model": "o4-mini",
          "output": [{
            "id": "rs_1", "type": "reasoning", "encrypted_content": "gAAAAAB",
            "summary": [
              {"type": "summary_text", "text": "Primero pense esto."},
              {"type": "summary_text", "text": "Despues esto otro."}
            ]
          }]
        }
    """.trimIndent()

    private val httpClient = FakeHttpClient()
    private val model = OpenAIChatModel("o4-mini", OpenAIConfig(apiKey = "sk-test"), httpClient)
}
