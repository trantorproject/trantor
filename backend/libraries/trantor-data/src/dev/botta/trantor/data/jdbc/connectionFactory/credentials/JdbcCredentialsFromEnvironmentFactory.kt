package dev.botta.trantor.data.jdbc.connectionFactory.credentials

import dev.botta.env.Env

class JdbcCredentialsFromEnvironmentFactory {
    fun create(prefix: String): JdbcCredentials {
        return JdbcCredentials(
            url = JdbcUrl(
                Env.getOrThrow("${prefix}_DRIVER"),
                Env.getOrThrow("${prefix}_HOST"),
                Env.getOrThrow("${prefix}_PORT").toInt(),
                Env.getOrThrow("${prefix}_NAME")
            ),
            userName = Env.getOrThrow("${prefix}_USER"),
            password = Env.getOrThrow("${prefix}_PASSWORD")
        )
    }
}
