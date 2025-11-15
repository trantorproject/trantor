package dev.botta.trantor.serviceProvider

sealed class ResolvedValue {
    data class Value(val value: Any?) : ResolvedValue()
    object Skip : ResolvedValue()
}
