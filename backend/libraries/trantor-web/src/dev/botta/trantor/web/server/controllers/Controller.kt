package dev.botta.trantor.web.server.controllers

interface Controller {
    fun registerRoutesIn(http: RouteRegister)
    fun getChildControllers() = emptyList<Controller>()
}
