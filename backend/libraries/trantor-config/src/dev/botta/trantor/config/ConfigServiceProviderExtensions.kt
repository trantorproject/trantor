package dev.botta.trantor.config

import dev.botta.trantor.serviceProvider.*

val ServiceProvider.config get() = this.get<Config>()
