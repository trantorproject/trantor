package dev.botta.trantor.primitives

import java.util.*

object CorrelationIdGenerator {
    fun new() = UUID.randomUUID().toString().replace("-", "").take(10)
}
