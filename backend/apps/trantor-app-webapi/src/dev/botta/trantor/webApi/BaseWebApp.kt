package dev.botta.trantor.webApi

abstract class BaseWebApp(protected val webApi: BaseWebApi) {
    val services = webApi.services
    val config = webApi.config
    val environment = webApi.environment

    fun start() = webApi.start()

    fun stop() = webApi.stop()
}
