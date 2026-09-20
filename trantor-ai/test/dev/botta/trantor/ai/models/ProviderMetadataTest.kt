@file:Suppress("ClassName")

package dev.botta.trantor.ai.models

import dev.botta.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProviderMetadataTest {
    @Test
    fun `returns the values of a provider`() {
        val metadata = ProviderMetadata.of("openai", Json.obj("itemId" to "msg_1"))

        assertThat(metadata["openai"]).isEqualTo(Json.obj("itemId" to "msg_1"))
    }

    @Test
    fun `returns null for a provider without values`() {
        val metadata = ProviderMetadata.of("openai", Json.obj("itemId" to "msg_1"))

        assertThat(metadata["anthropic"]).isNull()
    }

    @Test
    fun `keeps the values of each provider apart`() {
        val metadata = ProviderMetadata
            .of("openai", Json.obj("itemId" to "msg_1"))
            .with("anthropic", Json.obj("signature" to "abc"))

        assertThat(metadata["openai"]).isEqualTo(Json.obj("itemId" to "msg_1"))
        assertThat(metadata["anthropic"]).isEqualTo(Json.obj("signature" to "abc"))
        assertThat(metadata.providers).containsExactlyInAnyOrder("openai", "anthropic")
    }

    @Test
    fun `replaces the values of a provider`() {
        val metadata = ProviderMetadata
            .of("openai", Json.obj("itemId" to "msg_1"))
            .with("openai", Json.obj("itemId" to "msg_2"))

        assertThat(metadata["openai"]).isEqualTo(Json.obj("itemId" to "msg_2"))
    }

    @Test
    fun `none has no values`() {
        assertThat(ProviderMetadata.None.isEmpty).isTrue()
        assertThat(ProviderMetadata.None["openai"]).isNull()
    }

    @Test
    fun `adding values does not change the original`() {
        val original = ProviderMetadata.of("openai", Json.obj("itemId" to "msg_1"))

        original.with("anthropic", Json.obj("signature" to "abc"))

        assertThat(original.providers).containsExactly("openai")
    }
}
