@file:Suppress("ClassName")

package dev.botta.trantor.ai.models.chat

import dev.botta.trantor.ai.generation.NoObjectGeneratedError
import dev.botta.trantor.ai.models.ResponseInfo
import dev.botta.trantor.ai.testing.SkuTool
import dev.botta.trantor.serialization.gson.GsonSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/** The answer read as the object that was asked for, by the serializer of the application. */
class StructuredOutputTest {
    @Test
    fun `is read with the types the serializer registered`() {
        val response = answered("""{"sku":"ABC-1","units":12}""")

        assertThat(response.objectAs<Stocked>(SkuTool.serializer())).isEqualTo(Stocked(SkuTool.Sku("ABC-1"), 12))
    }

    @Test
    fun `a field the type does not have is left out, not a reason to lose the answer`() {
        val response = answered("""{"city":"Bariloche","celsius":7,"sky":"nublado"}""")

        assertThat(response.objectAs<Weather>(serializer)).isEqualTo(Weather("Bariloche", 7))
    }

    @Test
    fun `a list is read with the type of what it holds`() {
        val response = answered("""[{"city":"Bariloche","celsius":7},{"city":"Salta","celsius":24}]""")

        assertThat(response.objectAs<List<Weather>>(serializer))
            .containsExactly(Weather("Bariloche", 7), Weather("Salta", 24))
    }

    @Test
    fun `an answer without a field the type needs is not the object, and says what came`() {
        val response = answered("""{"city":"Bariloche"}""")

        assertThatThrownBy { response.objectAs<Weather>(serializer) }
            .isInstanceOfSatisfying(NoObjectGeneratedError::class.java) {
                assertThat(it.text).isEqualTo("""{"city":"Bariloche"}""")
            }
    }

    @Test
    fun `an answer that is not json is not the object either`() {
        val response = answered("Hacen 7 grados")

        assertThatThrownBy { response.objectAs<Weather>(serializer) }.isInstanceOf(NoObjectGeneratedError::class.java)
    }

    @Test
    fun `a refusal is not the object`() {
        val response = ChatResponse(listOf(RefusalPart("No puedo")), FinishReasons.Refusal, info)

        assertThatThrownBy { response.objectAs<Weather>(serializer) }
            .isInstanceOfSatisfying(NoObjectGeneratedError::class.java) {
                assertThat(it.refusal).isEqualTo("No puedo")
            }
    }

    private fun answered(text: String) = ChatResponse(listOf(TextPart(text)), FinishReasons.Stop, info)

    private val info = ResponseInfo(model = "m", provider = "p", latency = 1.milliseconds)

    private val serializer = GsonSerializer()

    data class Weather(val city: String, val celsius: Int)

    data class Stocked(val sku: SkuTool.Sku, val units: Int)
}
