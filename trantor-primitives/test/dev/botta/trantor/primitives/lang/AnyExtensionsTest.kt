@file:Suppress("ClassName")

package dev.botta.trantor.primitives.lang

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AnyExtensionsTest {
    @Nested
    inner class `describe` {
        @Test
        fun `with no parts is just the class name`() {
            assertThat(Point(1, 2).describe()).isEqualTo("Point()")
        }

        @Test
        fun `with a single value`() {
            assertThat(Point(1, 2).describe(42)).isEqualTo("Point(42)")
        }

        @Test
        fun `with named pairs`() {
            val described = Point(1, 2).describe("x" to 1, "y" to 2)

            assertThat(described).isEqualTo("Point(x=1, y=2)")
        }

        @Test
        fun `with parts already written out`() {
            val described = Point(1, 2).describe("x=1", "y=2")

            assertThat(described).isEqualTo("Point(x=1, y=2)")
        }

        @Test
        fun `keeps nulls visible instead of hiding them`() {
            assertThat(Point(1, 2).describe("name" to null)).isEqualTo("Point(name=null)")
            assertThat(Point(1, 2).describe(null)).isEqualTo("Point(null)")
        }

        @Test
        fun `names the runtime class, not the declared one`() {
            val point: Any = ThreeDPoint()

            assertThat(point.describe()).isEqualTo("ThreeDPoint()")
        }

        @Test
        fun `an anonymous object has no name to give`() {
            val anonymous = object {}

            assertThat(anonymous.describe()).isEqualTo("null()")
        }
    }

    private open class Point(val x: Int, val y: Int)

    private class ThreeDPoint: Point(0, 0)
}
