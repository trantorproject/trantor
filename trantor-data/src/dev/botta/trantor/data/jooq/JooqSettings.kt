package dev.botta.trantor.data.jooq

data class JooqSettings(
    var logSql: Boolean = false,
    var dialect: String? = null,
    var optimisticLocking: Boolean = false,
    var translateErrors: Boolean = true,
)
