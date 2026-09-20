@file:Suppress("ClassName")

package dev.botta.trantor.primitives.lang

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ClassExtensionsTest {
    @Nested
    inner class `shortName` {
        @Test
        fun `abbreviates the package to its initials and keeps the class`() {
            assertThat(Sample::class.java.shortName())
                .isEqualTo("d.b.t.p.l.ClassExtensionsTest\$Sample")
        }

        @Test
        fun `works for classes outside Trantor`() {
            assertThat(String::class.java.shortName()).isEqualTo("j.l.String")
        }

        @Test
        fun `a type with no package keeps only a leading dot`() {
            // Nothing in Trantor lives in the default package, but the format should not surprise a reader
            assertThat(Int::class.java.shortName()).isEqualTo(".int")
        }
    }

    private class Sample
}
