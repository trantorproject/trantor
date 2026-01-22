package dev.botta.trantor.web.server.controllers

import dev.botta.trantor.web.server.RouteRegister

interface Controller {
    fun registerRoutes(http: RouteRegister) {}
    fun getChildControllers() = emptyList<Controller>()
}
