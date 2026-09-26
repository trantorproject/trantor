package dev.botta.trantor.ai.history

import dev.botta.trantor.ai.models.chat.Message
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemorySessionTest {
    @Test
    fun `replacing what it keeps leaves only what it was given`() {
        val session = InMemorySession(listOf(Message.user("Hola"), Message.assistant("Hola!")))

        session.replace(listOf(Message.Summary("Nico saludo")))

        assertThat(session.load()).containsExactly(Message.Summary("Nico saludo"))
    }
}
