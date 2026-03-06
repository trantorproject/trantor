package dev.botta.trantor.web.application

import dev.botta.trantor.web.application.routes.ApplicationRouteRegister

class ApplicationControllerBase: ApplicationController {
    override fun registerRoutes(http: ApplicationRouteRegister) {
    }
}
