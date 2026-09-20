@file:Suppress("ClassName")

package dev.botta.trantor.web.client

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MultipartBodyTest {
    @Nested
    inner class `a field` {
        @Test
        fun `is kept with its name and value`() {
            body.addFieldPart("customer", "nico")

            val part = body.parts.first() as MultipartBody.FieldPart
            assertThat(part.name).isEqualTo("customer")
            assertThat(part.value).isEqualTo("nico")
        }

        @Test
        fun `may have no value, which is not the same as not being sent`() {
            body.addFieldPart("note", null)

            assertThat((body.parts.first() as MultipartBody.FieldPart).value).isNull()
        }

        @Test
        fun `carries its own headers when it was given some`() {
            body.addFieldPart("customer", "nico", mapOf("Content-Type" to "text/plain"))

            assertThat(body.parts.first().fields).containsEntry("Content-Type", "text/plain")
        }
    }

    @Nested
    inner class `a file` {
        @Test
        fun `is kept with everything the server needs to read it`() {
            body.addFilePart("invoice", "invoice.pdf", "application/pdf", "content".byteInputStream())

            val part = body.parts.first() as MultipartBody.FilePart
            assertThat(part.name).isEqualTo("invoice")
            assertThat(part.fileName).isEqualTo("invoice.pdf")
            assertThat(part.mimeType).isEqualTo("application/pdf")
            assertThat(part.data.readBytes()).isEqualTo("content".toByteArray())
        }

        @Test
        fun `the stream is held, not read, so a large file is not loaded into memory`() {
            val stream = "content".byteInputStream()

            body.addFilePart("invoice", "invoice.pdf", "application/pdf", stream)

            assertThat(stream.available()).isEqualTo("content".length)
        }
    }

    @Nested
    inner class `the body as a whole` {
        @Test
        fun `starts with no parts`() {
            assertThat(body.parts).isEmpty()
        }

        @Test
        fun `keeps the parts in the order they were added`() {
            body.addFieldPart("customer", "nico")
            body.addFilePart("invoice", "invoice.pdf", "application/pdf", "x".byteInputStream())
            body.addFieldPart("note", "urgent")

            assertThat(body.parts.map { it.name }).containsExactly("customer", "invoice", "note")
        }

        @Test
        fun `two parts may share a name, which is how a list is sent`() {
            body.addFieldPart("tags", "urgent")
            body.addFieldPart("tags", "gift")

            assertThat(body.parts).hasSize(2)
        }

        @Test
        fun `adding returns the body, so parts can be chained`() {
            val chained = MultipartBody()
                .addFieldPart("customer", "nico")
                .addFieldPart("note", "urgent")

            assertThat(chained.parts).hasSize(2)
        }
    }

    private val body = MultipartBody()
}
