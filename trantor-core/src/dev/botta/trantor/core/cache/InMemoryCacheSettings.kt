package dev.botta.trantor.core.cache

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class InMemoryCacheSettings(
    val expireAfter: Duration = 1.minutes,
    val maximumSize: Long = 1000L,
)
