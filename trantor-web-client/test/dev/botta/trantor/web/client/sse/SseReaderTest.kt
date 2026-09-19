@file:Suppress("ClassName")

package dev.botta.trantor.web.client.sse

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SseReaderTest {
    @Test
    fun `reads a single event`() {
        val events = read(
            "data: hello",
            "",
        )

        assertThat(events).containsExactly(SseEvent("hello"))
    }

    @Test
    fun `reads consecutive events`() {
        val events = read(
            "data: one",
            "",
            "data: two",
            "",
        )

        assertThat(events).containsExactly(SseEvent("one"), SseEvent("two"))
    }

    @Test
    fun `joins multiline data with line breaks`() {
        val events = read(
            "data: first",
            "data: second",
            "",
        )

        assertThat(events.single().data).isEqualTo("first\nsecond")
    }

    @Test
    fun `reads event name, id and retry`() {
        val events = read(
            "event: response.completed",
            "id: 42",
            "retry: 3000",
            "data: {}",
            "",
        )

        assertThat(events).containsExactly(SseEvent("{}", event = "response.completed", id = "42", retry = 3000))
    }

    @Test
    fun `fields of an event do not leak into the next one`() {
        val events = read(
            "event: first",
            "data: one",
            "",
            "data: two",
            "",
        )

        assertThat(events).containsExactly(SseEvent("one", event = "first"), SseEvent("two"))
    }

    @Test
    fun `ignores comments`() {
        val events = read(
            ": keep alive",
            "data: hello",
            "",
        )

        assertThat(events).containsExactly(SseEvent("hello"))
    }

    @Test
    fun `ignores unknown fields`() {
        val events = read(
            "whatever: something",
            "data: hello",
            "",
        )

        assertThat(events).containsExactly(SseEvent("hello"))
    }

    @Test
    fun `ignores a retry that is not a number`() {
        val events = read(
            "retry: soon",
            "data: hello",
            "",
        )

        assertThat(events.single().retry).isNull()
    }

    @Test
    fun `strips only the first space after the colon`() {
        val events = read(
            "data:  hello",
            "",
        )

        assertThat(events.single().data).isEqualTo(" hello")
    }

    @Test
    fun `reads a field without value`() {
        val events = read(
            "data",
            "",
        )

        assertThat(events.single().data).isEqualTo("")
    }

    @Test
    fun `ignores blocks without data`() {
        val events = read(
            "event: ping",
            "",
            "data: hello",
            "",
        )

        assertThat(events).containsExactly(SseEvent("hello"))
    }

    @Test
    fun `ignores repeated blank lines`() {
        val events = read(
            "data: hello",
            "",
            "",
            "",
        )

        assertThat(events).containsExactly(SseEvent("hello"))
    }

    @Test
    fun `dispatches the last event when the stream ends without a blank line`() {
        val events = read(
            "data: hello",
        )

        assertThat(events).containsExactly(SseEvent("hello"))
    }

    @Test
    fun `ignores carriage returns at the end of the line`() {
        val events = read(
            "event: response.completed\r",
            "data: hello\r",
            "\r",
        )

        assertThat(events).containsExactly(SseEvent("hello", event = "response.completed"))
    }

    @Test
    fun `reads lazily`() {
        var readLines = 0
        val lines = sequenceOf("data: one", "", "data: two", "").onEach { readLines++ }

        SseReader(lines).events().first()

        assertThat(readLines).isEqualTo(2)
    }

    private fun read(vararg lines: String) = SseReader(lines.asSequence()).events().toList()
}
