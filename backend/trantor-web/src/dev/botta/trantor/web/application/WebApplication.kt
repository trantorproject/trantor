package dev.botta.trantor.web.application

import com.google.gson.JsonParseException
import dev.botta.cqbus.*
import dev.botta.cqbus.requests.Request
import dev.botta.json.parser.JsonParseError
import dev.botta.lang.DetailsExt
import dev.botta.trantor.config.Config
import dev.botta.trantor.core.application.Application
import dev.botta.trantor.core.auth.*
import dev.botta.trantor.di.ServiceProvider
import dev.botta.trantor.domain.errors.*
import dev.botta.trantor.hosting.*
import dev.botta.trantor.web.application.requestmapper.ApplicationRequestMapper
import dev.botta.trantor.web.application.requestmapper.transformers.ApplicationRequestMapperJsonTransformer
import dev.botta.trantor.web.application.routes.*
import dev.botta.trantor.web.errorHandlers.*
import dev.botta.trantor.web.server.*
import dev.botta.trantor.web.server.controllers.Controller
import io.javalin.http.Context

class WebApplication(private val application: Application, private val requestMapper: ApplicationRequestMapper): Host, ApplicationRouteRegistrant, WebApplicationExecutor {
    override val services: ServiceProvider
        get() = application.services
    override val config: Config
        get() = application.config
    override val environment: HostEnvironment
        get() = application.environment
    val httpServer = services.get<HttpServer>()
    override val routes get() = ApplicationRouteRegister(httpServer.routes, requestMapper, this)

    override fun <T: Request<R>, R> execute(request: T, context: ExecutionContext): R {
        return application.execute(request, context)
    }

    override fun <T: Request<R>, R> execute(request: T, context: Context, executionContext: ExecutionContext): R {
        return execute(request, executionContext.with("javalin_context", context))
    }

    override fun registerMiddleware(middleware: Middleware, priority: MiddlewarePriorities) {
        return application.registerMiddleware(middleware, priority)
    }

    init {
        addKnownExceptions()
        routes.before { services.enterScope() }
        routes.after { services.leaveScope() }
        routes.wsBefore { services.enterScope()}
        routes.wsAfter { services.leaveScope()}
    }

    private fun addKnownExceptions() {
        addNotAuthenticatedError<NotAuthenticatedError>()
        addForbiddenError<UnauthorizedAccessError>()
        addForbiddenError<ForbiddenError>()
        addNotFoundError<NotFoundError>()
        addBadRequestError<DomainError>()
        addBadRequestError<JsonParseError>()
        addBadRequestError<JsonParseException>()
        addInternalError<Exception>()
    }

    fun <T: Exception> addErrorHandler(handler: BaseJsonErrorHandler<T>) {
        httpServer.addErrorHandler(handler)
    }

    fun addController(controller: ApplicationController) {
        controller.registerRoutes(routes)
    }

    inline fun <reified T: ApplicationController> addController() {
        addController(services.create<T>())
    }

    fun addController(controller: Controller) {
        httpServer.addController(controller)
    }

    fun addInterceptor(interceptor: HttpRequestInterceptor) {
        httpServer.addInterceptor(interceptor)
    }

    fun addRequestJsonTransformer(transformer: ApplicationRequestMapperJsonTransformer) {
        requestMapper.addRequestJsonTransformer(transformer)
    }

    override fun start() {
        application.start()
    }

    override fun stop(timeoutSeconds: Int) {
        application.stop(timeoutSeconds)
    }

    companion object {
        fun builder(args: Array<String>) = WebApplicationBuilder(WebApplicationBuilderConfig(args = args))

        fun builder(config: WebApplicationBuilderConfig) = WebApplicationBuilder(config)

        fun builder(details: DetailsExt<WebApplicationBuilderConfig> = {}) = WebApplicationBuilder(WebApplicationBuilderConfig().apply(details))
    }
}
