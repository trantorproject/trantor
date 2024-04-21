package dev.botta.trantor.web.server.controllers

import dev.botta.trantor.web.server.RouteRegister

interface Controller {
    fun registerRoutesIn(http: RouteRegister)
    fun getChildControllers() = emptyList<Controller>()
}
