package dev.botta.trantor.web.application

import dev.botta.trantor.web.application.routes.ApplicationRouteRegister

interface ApplicationController {
    fun registerRoutes(http: ApplicationRouteRegister)
}
