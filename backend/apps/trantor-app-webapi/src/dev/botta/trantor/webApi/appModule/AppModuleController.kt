package dev.botta.trantor.webApi.appModule

import dev.botta.cqbus.requests.Request
import dev.botta.trantor.appServices.AppModule
import dev.botta.trantor.core.serialization.JsonSerializer
import dev.botta.trantor.web.server.RouteRegister
import dev.botta.trantor.web.server.controllers.Controller
import io.ktor.http.*

abstract class AppModuleController(val dispatcher: AppModuleHttpDispatcher): Controller {
    constructor(appModule: AppModule, serializer: JsonSerializer): this(AppModuleHttpDispatcher(appModule, serializer))

    protected inline fun <reified T: Request<*>> get(http: RouteRegister, path: String) {
        http.get(path) { dispatcher.execute<T>(this) }
    }

    protected inline fun <reified T: Request<*>> post(http: RouteRegister, path: String, status: HttpStatusCode = HttpStatusCode.OK) {
        http.post(path) { dispatcher.execute<T>(this, status) }
    }

    protected inline fun <reified T: Request<*>> put(http: RouteRegister, path: String) {
        http.put(path) { dispatcher.execute<T>(this) }
    }

    protected inline fun <reified T: Request<*>> patch(http: RouteRegister, path: String) {
        http.patch(path) { dispatcher.execute<T>(this) }
    }

    protected inline fun <reified T: Request<*>> delete(http: RouteRegister, path: String) {
        http.delete(path) { dispatcher.execute<T>(this) }
    }
}
