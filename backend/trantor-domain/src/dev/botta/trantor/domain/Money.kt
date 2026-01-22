package dev.botta.trantor.domain

import java.math.*

class Money(private val amount: BigDecimal): Comparable<Money> {
    constructor(amount: Double): this(BigDecimal.valueOf(amount))

    constructor(amount: Float): this(BigDecimal(amount.toString()))

    constructor(amount: Int): this(BigDecimal(amount))

    constructor(amount: Long): this(BigDecimal.valueOf(amount))

    constructor(value: String): this(moneyStringToBigDecimal(value))

    operator fun plus(other: Money) = Money(amount + other.amount)

    operator fun minus(other: Money) = Money(amount - other.amount)

    operator fun times(multiplier: Money) = times(multiplier.amount)

    operator fun times(multiplier: BigDecimal) = Money(amount * multiplier)

    operator fun times(multiplier: Double) = this * Money(multiplier)

    operator fun times(multiplier: Float) = this * Money(multiplier)

    operator fun times(multiplier: Int) = this * Money(multiplier)

    operator fun times(multiplier: Long) = this * Money(multiplier)

    operator fun div(divider: Money) = div(divider.amount)

    operator fun div(divider: BigDecimal) = Money(amount.divide(divider))

    operator fun div(divider: Double) = this / Money(divider)

    operator fun div(divider: Float) = this / Money(divider)

    operator fun div(divider: Int) = this / Money(divider)

    operator fun div(divider: Long) = this / Money(divider)

    operator fun unaryMinus() = Money(-amount)

    fun isZero() = amount == BigDecimal.ZERO

    fun toBigDecimal() = amount

    fun toDouble() = amount.toDouble()

    override fun equals(other: Any?) = other is Money && other.amount.compareTo(amount) == 0

    override fun compareTo(other: Money) = amount.compareTo(other.amount)

    override fun hashCode() = amount.hashCode()

    fun isPositive() = this >= zero()

    override fun toString() = if (isPositive()) "$$amount" else "-$${amount.abs()}"

    fun plainString(): String = amount.toPlainString()

    fun unscaledValue() = amount.unscaledValue()

    fun unscaledValue(precision: Int) = amount.setScale(precision, RoundingMode.HALF_EVEN).unscaledValue()

    fun unscaledLongValue() = amount.unscaledValue().longValueExact()

    fun unscaledLongValue(precision: Int) = amount.setScale(precision, RoundingMode.HALF_EVEN).unscaledValue().longValueExact()

    companion object {
        fun zero() = Money(0)

        fun unscaledLong(value: Long, precision: Int) = Money(BigDecimal.valueOf(value, precision))

        fun unscaled(value: BigInteger, precision: Int) = Money(BigDecimal(value, precision))

        private fun moneyStringToBigDecimal(value: String): BigDecimal {
            return BigDecimal(value.replace("$", "").replace(" ", ""))
        }

        fun List<Money>.sum() = fold(zero()) { acc, value -> acc + value }
    }
}
