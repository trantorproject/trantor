package dev.botta.trantor.restApi.httpCQDispatcher

import dev.botta.cqbus.requests.Request
import dev.botta.trantor.web.server.controllers.*

abstract class CQController(val dispatcher: HttpCQDispatcher): Controller {
    protected inline fun <reified T: Request<*>> get(http: RouteRegister, path: String) {
        http.get(path) { dispatcher.execute<T>(it) }
    }

    protected inline fun <reified T: Request<*>> post(http: RouteRegister, path: String, statusCode: Int = 200) {
        http.post(path) { dispatcher.execute<T>(it, statusCode) }
    }

    protected inline fun <reified T: Request<*>> put(http: RouteRegister, path: String) {
        http.put(path) { dispatcher.execute<T>(it) }
    }

    protected inline fun <reified T: Request<*>> patch(http: RouteRegister, path: String) {
        http.patch(path) { dispatcher.execute<T>(it) }
    }

    protected inline fun <reified T: Request<*>> delete(http: RouteRegister, path: String) {
        http.delete(path) { dispatcher.execute<T>(it) }
    }
}
