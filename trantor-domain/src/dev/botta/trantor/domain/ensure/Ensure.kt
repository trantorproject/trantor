package dev.botta.trantor.domain.ensure

import dev.botta.time.Clock
import dev.botta.trantor.domain.errors.*
import java.math.BigDecimal
import java.time.*

object Ensure {
    // ---------- Booleans ----------
    fun isTrue(value: Boolean, field: String = "value") = apply {
        if (!value) fail("$field must be true")
    }

    inline fun isTrue(value: Boolean, error: () -> DomainError) = apply {
        if (!value) throw error()
    }

    fun isFalse(value: Boolean, field: String = "value") = apply {
        if (value) fail("$field must be false")
    }

    inline fun isFalse(value: Boolean, error: () -> DomainError) = apply {
        if (value) throw error()
    }

    // ---------- Strings ----------
    fun notBlank(value: String, field: String = "value") = apply {
        if (value.isBlank()) fail("$field must not be blank")
    }

    inline fun notBlank(value: String, error: () -> DomainError) = apply {
        if (value.isBlank()) throw error()
    }

    fun notEmpty(value: String, field: String = "value") = apply {
        if (value.isEmpty()) fail("$field must not be empty")
    }

    inline fun notEmpty(value: String, error: () -> DomainError) = apply {
        if (value.isEmpty()) throw error()
    }

    fun lengthBetween(value: String, range: IntRange, field: String = "value") = apply {
        if (value.length !in range) fail("$field length must be in $range, got ${value.length}")
    }

    inline fun lengthBetween(value: String, range: IntRange, error: () -> DomainError) = apply {
        if (value.length !in range) throw error()
    }

    fun maxLength(value: String, max: Int, field: String = "value") = apply {
        if (value.length > max) fail("$field length must be at most $max, got ${value.length}")
    }

    inline fun maxLength(value: String, max: Int, error: () -> DomainError) = apply {
        if (value.length > max) throw error()
    }

    fun minLength(value: String, min: Int, field: String = "value") = apply {
        if (value.length < min) fail("$field length must be at least $min, got ${value.length}")
    }

    inline fun minLength(value: String, min: Int, error: () -> DomainError) = apply {
        if (value.length < min) throw error()
    }

    fun matches(value: String, pattern: Regex, field: String = "value") = apply {
        if (!pattern.matches(value)) fail("$field has invalid format")
    }

    inline fun matches(value: String, pattern: Regex, error: () -> DomainError) = apply {
        if (!pattern.matches(value)) throw error()
    }

    fun startsWith(value: String, prefix: String, field: String = "value") = apply {
        if (!value.startsWith(prefix)) fail("$field must start with '$prefix'")
    }

    inline fun startsWith(value: String, prefix: String, error: () -> DomainError) = apply {
        if (!value.startsWith(prefix)) throw error()
    }

    fun endsWith(value: String, suffix: String, field: String = "value") = apply {
        if (!value.endsWith(suffix)) fail("$field must end with '$suffix'")
    }

    inline fun endsWith(value: String, suffix: String, error: () -> DomainError) = apply {
        if (!value.endsWith(suffix)) throw error()
    }

    fun contains(value: String, substring: String, field: String = "value") = apply {
        if (!value.contains(substring)) fail("$field must contain '$substring'")
    }

    inline fun contains(value: String, substring: String, error: () -> DomainError) = apply {
        if (!value.contains(substring)) throw error()
    }

    fun notContains(value: String, substring: String, field: String = "value") = apply {
        if (value.contains(substring)) fail("$field must contain '$substring'")
    }

    inline fun notContains(value: String, substring: String, error: () -> DomainError) = apply {
        if (value.contains(substring)) throw error()
    }

    // ---------- Numbers (Int) ----------

    fun positive(value: Int, field: String = "value") = apply {
        if (value <= 0) fail("$field must be positive, got $value")
    }

    inline fun positive(value: Int, error: () -> DomainError) = apply {
        if (value <= 0) throw error()
    }

    fun nonNegative(value: Int, field: String = "value") = apply {
        if (value < 0) fail("$field must be non-negative, got $value")
    }

    inline fun nonNegative(value: Int, error: () -> DomainError) = apply {
        if (value < 0) throw error()
    }

    fun negative(value: Int, field: String = "value") = apply {
        if (value >= 0) fail("$field must be negative, got $value")
    }

    inline fun negative(value: Int, error: () -> DomainError) = apply {
        if (value >= 0) throw error()
    }

    fun min(value: Int, min: Int, field: String = "value") = apply {
        if (value < min) fail("$field must be at least $min, got $value")
    }

    inline fun min(value: Int, min: Int, error: () -> DomainError) = apply {
        if (value < min) throw error()
    }

    fun max(value: Int, max: Int, field: String = "value") = apply {
        if (value > max) fail("$field must be at most $max, got $value")
    }

    inline fun max(value: Int, max: Int, error: () -> DomainError) = apply {
        if (value > max) throw error()
    }

    fun inRange(value: Int, range: IntRange, field: String = "value") = apply {
        if (value !in range) fail("$field must be in $range, got $value")
    }

    inline fun inRange(value: Int, range: IntRange, error: () -> DomainError) = apply {
        if (value !in range) throw error()
    }

    // ---------- Numbers (Long) ----------

    fun positive(value: Long, field: String = "value") = apply {
        if (value <= 0L) fail("$field must be positive, got $value")
    }

    inline fun positive(value: Long, error: () -> DomainError) = apply {
        if (value <= 0L) throw error()
    }

    fun nonNegative(value: Long, field: String = "value") = apply {
        if (value < 0L) fail("$field must be non-negative, got $value")
    }

    inline fun nonNegative(value: Long, error: () -> DomainError) = apply {
        if (value < 0L) throw error()
    }

    fun min(value: Long, min: Long, field: String = "value") = apply {
        if (value < min) fail("$field must be at least $min, got $value")
    }

    inline fun min(value: Long, min: Long, error: () -> DomainError) = apply {
        if (value < min) throw error()
    }

    fun max(value: Long, max: Long, field: String = "value") = apply {
        if (value > max) fail("$field must be at most $max, got $value")
    }

    inline fun max(value: Long, max: Long, error: () -> DomainError) = apply {
        if (value > max) throw error()
    }

    fun inRange(value: Long, range: LongRange, field: String = "value") = apply {
        if (value !in range) fail("$field must be in $range, got $value")
    }

    inline fun inRange(value: Long, range: LongRange, error: () -> DomainError) = apply {
        if (value !in range) throw error()
    }

    // ---------- Numbers (Double) ----------

    fun positive(value: Double, field: String = "value") = apply {
        if (value <= 0.0) fail("$field must be positive, got $value")
    }

    inline fun positive(value: Double, error: () -> DomainError) = apply {
        if (value <= 0.0) throw error()
    }

    fun nonNegative(value: Double, field: String = "value") = apply {
        if (value < 0.0) fail("$field must be non-negative, got $value")
    }

    inline fun nonNegative(value: Double, error: () -> DomainError) = apply {
        if (value < 0.0) throw error()
    }

    fun inRange(
        value: Double,
        range: ClosedFloatingPointRange<Double>,
        field: String = "value",
    ) = apply {
        if (value !in range) fail("$field must be in $range, got $value")
    }

    inline fun inRange(
        value: Double,
        range: ClosedFloatingPointRange<Double>,
        error: () -> DomainError,
    ) = apply {
        if (value !in range) throw error()
    }

    fun finite(value: Double, field: String = "value") = apply {
        if (!value.isFinite()) fail("$field must be finite, got $value")
    }

    inline fun finite(value: Double, error: () -> DomainError) = apply {
        if (!value.isFinite()) throw error()
    }

    // ---------- BigDecimal ----------

    fun positive(value: BigDecimal, field: String = "value") = apply {
        if (value.signum() <= 0) fail("$field must be positive, got $value")
    }

    inline fun positive(value: BigDecimal, error: () -> DomainError) = apply {
        if (value.signum() <= 0) throw error()
    }

    fun nonNegative(value: BigDecimal, field: String = "value") = apply {
        if (value.signum() < 0) fail("$field must be non-negative, got $value")
    }

    inline fun nonNegative(value: BigDecimal, error: () -> DomainError) = apply {
        if (value.signum() < 0) throw error()
    }

    fun min(value: BigDecimal, min: BigDecimal, field: String = "value") = apply {
        if (value < min) fail("$field must be at least $min, got $value")
    }

    inline fun min(value: BigDecimal, min: BigDecimal, error: () -> DomainError) = apply {
        if (value < min) throw error()
    }

    fun max(value: BigDecimal, max: BigDecimal, field: String = "value") = apply {
        if (value > max) fail("$field must be at most $max, got $value")
    }

    inline fun max(value: BigDecimal, max: BigDecimal, error: () -> DomainError) = apply {
        if (value > max) throw error()
    }

    // ---------- Comparable ----------

    fun <T: Comparable<T>> greaterThan(value: T, other: T, field: String = "value") = apply {
        if (value <= other) fail("$field must be greater than $other, got $value")
    }

    inline fun <T: Comparable<T>> greaterThan(
        value: T,
        other: T,
        error: () -> DomainError,
    ) = apply {
        if (value <= other) throw error()
    }

    fun <T: Comparable<T>> greaterThanOrEqual(
        value: T,
        other: T,
        field: String = "value",
    ) = apply {
        if (value < other) fail("$field must be greater than or equal to $other, got $value")
    }

    inline fun <T: Comparable<T>> greaterThanOrEqual(
        value: T,
        other: T,
        error: () -> DomainError,
    ) = apply {
        if (value < other) throw error()
    }

    fun <T: Comparable<T>> lessThan(value: T, other: T, field: String = "value") = apply {
        if (value >= other) fail("$field must be less than $other, got $value")
    }

    inline fun <T: Comparable<T>> lessThan(
        value: T,
        other: T,
        error: () -> DomainError,
    ) = apply {
        if (value >= other) throw error()
    }

    fun <T: Comparable<T>> lessThanOrEqual(
        value: T,
        other: T,
        field: String = "value",
    ) = apply {
        if (value > other) fail("$field must be less than or equal to $other, got $value")
    }

    inline fun <T: Comparable<T>> lessThanOrEqual(
        value: T,
        other: T,
        error: () -> DomainError,
    ) = apply {
        if (value > other) throw error()
    }

    fun <T: Comparable<T>> between(
        value: T,
        range: ClosedRange<T>,
        field: String = "value",
    ) = apply {
        if (value !in range) fail("$field must be in $range, got $value")
    }

    inline fun <T: Comparable<T>> between(
        value: T,
        range: ClosedRange<T>,
        error: () -> DomainError,
    ) = apply {
        if (value !in range) throw error()
    }

    // ---------- Equality ----------

    fun equal(value: Any?, expected: Any?, field: String = "value") = apply {
        if (value != expected) fail("$field must equal $expected, got $value")
    }

    inline fun equal(value: Any?, expected: Any?, error: () -> DomainError) = apply {
        if (value != expected) throw error()
    }

    fun notEqual(value: Any?, other: Any?, field: String = "value") = apply {
        if (value == other) fail("$field must not equal $other")
    }

    inline fun notEqual(value: Any?, other: Any?, error: () -> DomainError) = apply {
        if (value == other) throw error()
    }

    // ---------- Collections ----------

    fun notEmpty(value: Collection<*>, field: String = "value") = apply {
        if (value.isEmpty()) fail("$field must not be empty")
    }

    inline fun notEmpty(value: Collection<*>, error: () -> DomainError) = apply {
        if (value.isEmpty()) throw error()
    }

    fun empty(value: Collection<*>, field: String = "value") = apply {
        if (value.isNotEmpty()) fail("$field must be empty")
    }

    inline fun empty(value: Collection<*>, error: () -> DomainError) = apply {
        if (value.isNotEmpty()) throw error()
    }

    fun sizeBetween(value: Collection<*>, range: IntRange, field: String = "value") = apply {
        if (value.size !in range) fail("$field size must be in $range, got ${value.size}")
    }

    inline fun sizeBetween(
        value: Collection<*>,
        range: IntRange,
        error: () -> DomainError,
    ) = apply {
        if (value.size !in range) throw error()
    }

    fun minSize(value: Collection<*>, min: Int, field: String = "value") = apply {
        if (value.size < min) fail("$field size must be at least $min, got ${value.size}")
    }

    inline fun minSize(value: Collection<*>, min: Int, error: () -> DomainError) = apply {
        if (value.size < min) throw error()
    }

    fun maxSize(value: Collection<*>, max: Int, field: String = "value") = apply {
        if (value.size > max) fail("$field size must be at most $max, got ${value.size}")
    }

    inline fun maxSize(value: Collection<*>, max: Int, error: () -> DomainError) = apply {
        if (value.size > max) throw error()
    }

    fun <T> contains(value: Collection<T>, element: T, field: String = "value") = apply {
        if (element !in value) fail("$field must contain $element")
    }

    inline fun <T> contains(
        value: Collection<T>,
        element: T,
        error: () -> DomainError,
    ) = apply {
        if (element !in value) throw error()
    }

    fun <T> notContains(value: Collection<T>, element: T, field: String = "value") = apply {
        if (element in value) fail("$field must not contain $element")
    }

    inline fun <T> notContains(
        value: Collection<T>,
        element: T,
        error: () -> DomainError,
    ) = apply {
        if (element in value) throw error()
    }

    fun <T> unique(value: Collection<T>, field: String = "value") = apply {
        if (value.size != value.toSet().size) fail("$field must contain unique elements")
    }

    inline fun <T> unique(value: Collection<T>, error: () -> DomainError) = apply {
        if (value.size != value.toSet().size) throw error()
    }

    fun <T> allMatch(
        value: Collection<T>,
        field: String = "value",
        predicate: (T) -> Boolean,
    ) = apply {
        if (!value.all(predicate)) fail("$field has elements that do not satisfy the condition")
    }

    inline fun <T> allMatch(
        value: Collection<T>,
        error: () -> DomainError,
        predicate: (T) -> Boolean,
    ) = apply {
        if (!value.all(predicate)) throw error()
    }

    fun <T> noneMatch(
        value: Collection<T>,
        field: String = "value",
        predicate: (T) -> Boolean,
    ) = apply {
        if (value.any(predicate)) fail("$field has elements that should not be present")
    }

    inline fun <T> noneMatch(
        value: Collection<T>,
        error: () -> DomainError,
        predicate: (T) -> Boolean,
    ) = apply {
        if (value.any(predicate)) throw error()
    }

    // ---------- Maps ----------

    fun notEmpty(value: Map<*, *>, field: String = "value") = apply {
        if (value.isEmpty()) fail("$field must not be empty")
    }

    inline fun notEmpty(value: Map<*, *>, error: () -> DomainError) = apply {
        if (value.isEmpty()) throw error()
    }

    fun <K> hasKey(value: Map<K, *>, key: K, field: String = "value") = apply {
        if (!value.containsKey(key)) fail("$field must contain key $key")
    }

    inline fun <K> hasKey(value: Map<K, *>, key: K, error: () -> DomainError) = apply {
        if (!value.containsKey(key)) throw error()
    }

    // ---------- Dates / Time ----------

    fun inPast(
        value: LocalDate,
        field: String = "value",
    ) = apply {
        if (!value.isBefore(Clock.today())) fail("$field must be in the past")
    }

    inline fun inPast(
        value: LocalDate,
        error: () -> DomainError,
    ) = apply {
        if (!value.isBefore(Clock.today())) throw error()
    }

    fun inFuture(
        value: LocalDate,
        field: String = "value",
    ) = apply {
        if (!value.isAfter(Clock.today())) fail("$field must be in the future")
    }

    inline fun inFuture(
        value: LocalDate,
        error: () -> DomainError,
    ) = apply {
        if (!value.isAfter(Clock.today())) throw error()
    }

    fun inPast(
        value: LocalDateTime,
        field: String = "value",
    ) = apply {
        if (!value.isBefore(Clock.now())) fail("$field must be in the past")
    }

    inline fun inPast(
        value: LocalDateTime,
        error: () -> DomainError,
    ) = apply {
        if (!value.isBefore(Clock.now())) throw error()
    }

    fun inFuture(
        value: LocalDateTime,
        field: String = "value",
    ) = apply {
        if (!value.isAfter(Clock.now())) fail("$field must be in the future")
    }

    inline fun inFuture(
        value: LocalDateTime,
        error: () -> DomainError,
    ) = apply {
        if (!value.isAfter(Clock.now())) throw error()
    }

    fun before(value: LocalDateTime, other: LocalDateTime, field: String = "value") = apply {
        if (!value.isBefore(other)) fail("$field must be before $other")
    }

    inline fun before(value: LocalDateTime, other: LocalDateTime, error: () -> DomainError) = apply {
        if (!value.isBefore(other)) throw error()
    }

    fun after(value: LocalDateTime, other: LocalDateTime, field: String = "value") = apply {
        if (!value.isAfter(other)) fail("$field must be after $other")
    }

    inline fun after(value: LocalDateTime, other: LocalDateTime, error: () -> DomainError) = apply {
        if (!value.isAfter(other)) throw error()
    }

    fun before(value: LocalDate, other: LocalDate, field: String = "value") = apply {
        if (!value.isBefore(other)) fail("$field must be before $other")
    }

    inline fun before(value: LocalDate, other: LocalDate, error: () -> DomainError) = apply {
        if (!value.isBefore(other)) throw error()
    }

    fun after(value: LocalDate, other: LocalDate, field: String = "value") = apply {
        if (!value.isAfter(other)) fail("$field must be after $other")
    }

    inline fun after(value: LocalDate, other: LocalDate, error: () -> DomainError) = apply {
        if (!value.isAfter(other)) throw error()
    }

    // ---------- Enums / oneOf ----------

    fun <E: Enum<E>> oneOf(value: E, allowed: Set<E>, field: String = "value") = apply {
        if (value !in allowed) fail("$field must be one of $allowed, got $value")
    }

    inline fun <E: Enum<E>> oneOf(
        value: E,
        allowed: Set<E>,
        error: () -> DomainError,
    ) = apply {
        if (value !in allowed) throw error()
    }

    fun <T> oneOf(value: T, allowed: Collection<T>, field: String = "value") = apply {
        if (value !in allowed) fail("$field must be one of $allowed, got $value")
    }

    inline fun <T> oneOf(
        value: T,
        allowed: Collection<T>,
        error: () -> DomainError,
    ) = apply {
        if (value !in allowed) throw error()
    }
}

inline fun ensure(block: Ensure.() -> Unit) {
    Ensure.apply(block)
}
