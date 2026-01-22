package dev.botta.trantor.di

sealed class ResolvedValue {
    data class Value(val value: Any?) : ResolvedValue()
    object Skip : ResolvedValue()
}
