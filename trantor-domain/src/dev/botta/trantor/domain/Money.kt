package dev.botta.trantor.domain

import dev.botta.trantor.domain.ensure.Ensure
import dev.botta.trantor.domain.ensure.ensure
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

    // An exact division throws when the result never ends (1 / 3), so it keeps 34 significant digits instead
    operator fun div(divider: BigDecimal) = Money(amount.divide(divider, MathContext.DECIMAL128))

    operator fun div(divider: Double) = this / Money(divider)

    operator fun div(divider: Float) = this / Money(divider)

    operator fun div(divider: Int) = this / Money(divider)

    operator fun div(divider: Long) = this / Money(divider)

    operator fun unaryMinus() = Money(-amount)

    fun rounded(precision: Int) = Money(amount.setScale(precision, RoundingMode.HALF_EVEN))

    fun allocate(parts: Int, precision: Int): List<Money> {
        Ensure.positive(parts, "parts")
        return allocate(List(parts) { 1 }, precision)
    }

    /**
     * Splits the amount, rounded to [precision], in parts proportional to [ratios] that add up to it exactly.
     *
     * Dividing cannot do this: 100 / 3 rounded is 33.33, and three of them are 99.99. The minor units the division
     * leaves over go one each to the first parts, skipping those with a zero ratio.
     */
    fun allocate(ratios: List<Int>, precision: Int): List<Money> {
        ensure {
            notEmpty(ratios, "ratios")
            allMatch(ratios, "ratios") { it >= 0 }
            positive(ratios.sum(), "sum of ratios")
        }
        if (amount.signum() < 0) return (-this).allocate(ratios, precision).map { -it }

        val total = unscaledValue(precision)
        val ratioSum = ratios.sum().toBigInteger()
        val shares = ratios.map { total * it.toBigInteger() / ratioSum }.toMutableList()
        var remainder = total - shares.fold(BigInteger.ZERO, BigInteger::add)
        for (index in ratios.indices) {
            if (remainder.signum() == 0) break
            if (ratios[index] == 0) continue
            shares[index] += BigInteger.ONE
            remainder -= BigInteger.ONE
        }
        return shares.map { unscaled(it, precision) }
    }

    fun isZero() = amount.signum() == 0

    fun toBigDecimal() = amount

    fun toDouble() = amount.toDouble()

    override fun equals(other: Any?) = other is Money && other.amount.compareTo(amount) == 0

    override fun compareTo(other: Money) = amount.compareTo(other.amount)

    // Equality ignores the scale (2.0 == 2.00), so the hash has to ignore it too
    override fun hashCode() = amount.stripTrailingZeros().hashCode()

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
