package dev.botta.trantor.core.cache

import java.time.Duration

data class InMemoryCacheSettings(
    val maximumSize: Long = 100L,
    val expirationAfterWrite: Duration = Duration.ofMinutes(1),
    val writeAfterCommit: Boolean = true,
)
