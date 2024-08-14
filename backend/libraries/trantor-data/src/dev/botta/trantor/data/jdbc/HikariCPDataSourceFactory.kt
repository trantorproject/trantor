package dev.botta.trantor.data.jdbc

import com.zaxxer.hikari.*
import dev.botta.trantor.data.jdbc.credentials.JdbcCredentials

class HikariDataSourceFactory {
    fun create(credentials: JdbcCredentials, maxPoolSize: Int = 10): HikariDataSource {
        val config = HikariConfig()
        config.maximumPoolSize = maxPoolSize
        config.jdbcUrl = credentials.url.toString()
        config.username = credentials.userName
        config.password = credentials.password
        return HikariDataSource(config)
    }
}
