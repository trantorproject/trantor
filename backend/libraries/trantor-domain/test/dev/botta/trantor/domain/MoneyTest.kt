package dev.botta.trantor.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.*
import java.math.*

class MoneyTest {
    @Test
    fun `Money can be initialized from a big decimal`() {
        assertThat(Money(BigDecimal.valueOf(100.42)).toString()).isEqualTo("$100.42")
        assertThat(Money(BigDecimal.valueOf(0.3333333339)).toString()).isEqualTo("$0.3333333339")
        assertThat(Money(BigDecimal(999)).toString()).isEqualTo("$999")
        assertThat(Money(BigDecimal.valueOf(1234567890, 5)).toString()).isEqualTo("$12345.67890")
    }

    @Test
    fun `Money can be initialized from a string`() {
        assertThat(Money("100.42").toString()).isEqualTo("$100.42")
        assertThat(Money("$100.42").toString()).isEqualTo("$100.42")
    }

    @Test
    fun `Money can be initialized from a double`() {
        assertThat(Money(100.55).toString()).isEqualTo("$100.55")
        assertThat(Money(0.77776).toString()).isEqualTo("$0.77776")
        assertThat(Money(0.3333333339).toString()).isEqualTo("$0.3333333339")
    }

    @Test
    fun `Money can be initialized from a float`() {
        assertThat(Money(100.55f).toString()).isEqualTo("$100.55")
        assertThat(Money(0.77776f).toString()).isEqualTo("$0.77776")
        assertThat(Money(0.3333331f).toString()).isEqualTo("$0.3333331")
    }

    @Test
    fun `Money can be initialized from an int`() {
        assertThat(Money(100).toString()).isEqualTo("$100")
        assertThat(Money(155).toString()).isEqualTo("$155")
    }

    @Test
    fun `Money can be initialized from a long`() {
        assertThat(Money(1000000000000000).toString()).isEqualTo("$1000000000000000")
        assertThat(Money(1558723947238794232).toString()).isEqualTo("$1558723947238794232")
    }

    @Test
    fun `Money can be initialized from an unscaled long with precision`() {
        assertThat(Money.unscaledLong(1000_000L, 3).toString()).isEqualTo("$1000.000")
        assertThat(Money.unscaledLong(12_345678L, 6).toString()).isEqualTo("$12.345678")
    }

    @Test
    fun `Money can be initialized from an unscaled big integer with precision`() {
        assertThat(Money.unscaled(BigInteger("1000000"), 3).toString()).isEqualTo("$1000.000")
        assertThat(Money.unscaled(BigInteger("12345678"), 6).toString()).isEqualTo("$12.345678")
    }

    @Test
    fun plus() {
        assertThat(Money(100) + Money(200)).isEqualTo(Money(300))
        assertThat(Money(-20) + Money(10)).isEqualTo(Money(-10))
        assertThat(Money(2.345) + Money(1.1111)).isEqualTo(Money(3.4561))
    }

    @Test
    fun minus() {
        assertThat(Money(50) - Money(200)).isEqualTo(Money(-150))
        assertThat(Money(30) - Money(10)).isEqualTo(Money(20))
        assertThat(Money(2.3452) - Money(1.1111)).isEqualTo(Money(1.2341))
    }

    @Test
    fun times() {
        assertThat(Money(50) * Money(3.4)).isEqualTo(Money(170))
        assertThat(Money(50) * 2).isEqualTo(Money(100))
        assertThat(Money(10) * 2.5).isEqualTo(Money(25))
        assertThat(Money(10) * 2.5f).isEqualTo(Money(25))
        assertThat(Money(10) * 20L).isEqualTo(Money(200))
        assertThat(Money(3.4) * BigDecimal.valueOf(2.5111)).isEqualTo(Money(8.53774))
    }

    @Test
    fun div() {
        assertThat(Money(50) / Money(2)).isEqualTo(Money(25))
        assertThat(Money(150) / 80).isEqualTo(Money(1.875))
        assertThat(Money(50) / 2L).isEqualTo(Money(25))
        assertThat(Money(40) / 2.0).isEqualTo(Money(20))
        assertThat(Money(40) / 2.0f).isEqualTo(Money(20))
        assertThat(Money(40) / BigDecimal.valueOf(2.0)).isEqualTo(Money(20))
    }

    @Test
    fun unaryMinus() {
        assertThat(-Money(50)).isEqualTo(Money(-50))
    }

    @Test
    fun isZero() {
        assertThat(Money(0).isZero()).isTrue
        assertThat(Money(0.00001).isZero()).isFalse
        assertThat(Money(-0.00001).isZero()).isFalse
    }

    @Test
    fun isPositive() {
        assertThat(Money(0).isPositive()).isTrue
        assertThat(Money(0.00001).isPositive()).isTrue
        assertThat(Money(-0.00001).isPositive()).isFalse
    }

    @Test
    fun equality() {
        assertThat(Money(50.0)).isEqualTo(Money(50))
        assertThat(Money(50.0)).isEqualTo(Money(50.00))
        assertThat(Money("-$100")).isEqualTo(Money(-100))
        assertThat(Money("- $  100")).isEqualTo(Money(-100))
    }

    @Test
    fun comparison() {
        assertThat(Money(50)).isGreaterThan(Money(10.23))
        assertThat(Money(123.23)).isLessThan(Money(200))
    }

    @Test
    fun `toString returns a string representation of the money`() {
        assertThat(Money(BigDecimal("10.4")).toString()).isEqualTo("$10.4")
        assertThat(Money("-$100").toString()).isEqualTo("-$100")
        assertThat(Money(2.34).toString()).isEqualTo("$2.34")
        assertThat(Money(0).toString()).isEqualTo("$0")
        assertThat(Money(-0).toString()).isEqualTo("$0")
    }

    @Test
    fun `plainString returns a string representation of the money without $ symbol`() {
        assertThat(Money(BigDecimal("10.4")).plainString()).isEqualTo("10.4")
        assertThat(Money("-$100").plainString()).isEqualTo("-100")
    }

    @Test
    fun `unscaledValue returns unscaled value`() {
        assertThat(Money(2).unscaledValue()).isEqualTo(BigInteger("2"))
        assertThat(Money(2.34).unscaledValue()).isEqualTo(BigInteger("234"))
        assertThat(Money("1000.1234567890").unscaledValue()).isEqualTo(BigInteger("10001234567890"))
    }

    @Test
    fun `unscaledValue returns unscaled value with given precision`() {
        assertThat(Money(2).unscaledValue(8)).isEqualTo(BigInteger("200000000"))
        assertThat(Money(2.34).unscaledValue(8)).isEqualTo(BigInteger("234000000"))
        assertThat(Money("1000.1234567890").unscaledValue(8)).isEqualTo(BigInteger("100012345679"))
        assertThat(Money(2.34).unscaledValue(1)).isEqualTo(BigInteger("23"))
        assertThat(Money(2.35).unscaledValue(1)).isEqualTo(BigInteger("24"))
        assertThat(Money(-2.34).unscaledValue(1)).isEqualTo(BigInteger("-23"))
        assertThat(Money(-2.35).unscaledValue(1)).isEqualTo(BigInteger("-24"))
    }

    @Test
    fun `unscaledLongValue returns unscaled value as long`() {
        assertThat(Money(2).unscaledLongValue()).isEqualTo(2L)
        assertThat(Money(2.34).unscaledLongValue()).isEqualTo(234L)
        assertThat(Money("1000.1234567890").unscaledLongValue()).isEqualTo(10001234567890L)
    }

    @Test
    fun `unscaledLongValue fails if value doesn't fit in a long`() {
        assertThrows<ArithmeticException> {
            Money("3214553536.1361525377").unscaledLongValue()
        }
    }

    @Test
    fun `unscaledLongValue returns unscaled value with given precision as long`() {
        assertThat(Money(2).unscaledLongValue(8)).isEqualTo(200000000L)
        assertThat(Money(2.34).unscaledLongValue(8)).isEqualTo(234000000L)
        assertThat(Money("1000.1234567890").unscaledLongValue(8)).isEqualTo(100012345679L)
        assertThat(Money(2.34).unscaledLongValue(1)).isEqualTo(23L)
        assertThat(Money(2.35).unscaledLongValue(1)).isEqualTo(24L)
        assertThat(Money(-2.34).unscaledLongValue(1)).isEqualTo(-23L)
        assertThat(Money(-2.35).unscaledLongValue(1)).isEqualTo(-24L)
    }

    @Test
    fun `unscaledLongValue with given precision fails if value doesn't fit in a long`() {
        assertThrows<ArithmeticException> {
            Money("3214553536.1361525377").unscaledLongValue(12)
        }
    }
}
