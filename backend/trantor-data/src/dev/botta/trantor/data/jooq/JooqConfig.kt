package dev.botta.trantor.data.jooq

data class JooqConfig(
    var logSql: Boolean = false,
    var dialect: String? = null,
)
