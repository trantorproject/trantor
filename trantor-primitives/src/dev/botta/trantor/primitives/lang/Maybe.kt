package dev.botta.trantor.primitives.lang

sealed class Maybe<out T> {
    object None : Maybe<Nothing>()
    data class Value<T>(val value: T) : Maybe<T>()

    fun ifValue(exec: (value: T) -> Unit) {
        if (this !is Value) return
        exec(value)
    }

    fun isNone() = this is None

    fun hasValue() = this is Value

    companion object {
        fun <T> of(value: T): Maybe<T> = Value(value)
    }
}
