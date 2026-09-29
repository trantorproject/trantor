# trantor-web

The HTTP side of Trantor, on top of **Javalin** (Jetty). It gives a server that is a hosted service, a
route registry, controllers, error handlers and websockets, plus a way to map an HTTP request straight
onto a use case.

---

## The server

```kotlin
services.addHttpServer()
```

`addHttpServer()` reads `httpServer` from configuration into `HttpServerSettings`, registers the
`HttpServer`, and adds it as a `HostedService` — so it starts and stops with the host.

```json
{ "httpServer": { "port": 8080, "maxThreads": 16, "isMetricsEnabled": true } }
```

`HttpServerSettings` also carries two lambdas for what configuration cannot express:

```kotlin
services.configure<HttpServerSettings> { settings, _ ->
    settings.configureJavalin = { javalin -> ... }
    settings.requestLoggerFactory = { logger -> MyRequestLogger(logger) }
}
```

When there is an `OpenTelemetry` in the container, every request is a `SERVER` span named after its route
(`GET /orders/{id}`), which goes on from the trace of the caller. See
[trantor-opentelemetry](trantor-opentelemetry.md).

---

## Routes and controllers

A `RouteRegister` is the thing you register paths on: `get`, `post`, `put`, `patch`, `delete`, the
`before` / `after` filters, and `ws` for websockets. A `Controller` groups the routes of one resource:

```kotlin
class OrdersController: Controller {
    override fun registerRoutes(http: RouteRegister) {
        http.get("/orders/{id}") { ctx -> ... }
        http.post("/orders") { ctx -> ... }
    }
}
```

`RouteRegistrant` is the same API as extension functions, for anything that holds a `RouteRegister`
rather than being handed one.

`ContextExtensions` adds the Trantor-specific helpers over Javalin's `Context`, including `jsonError`.

---

## A route that is a use case

`WebApplication` is an `Application` with an HTTP server, so a route can be declared as the use case it
executes instead of as a handler:

```kotlin
val app = WebApplication.builder(args).build()

app.routes.post<PlaceOrder>("/orders", statusCode = 201)
app.routes.get<GetOrder>("/orders/{id}")
```

`ApplicationRequestMapper` builds the request object out of the HTTP call. It starts from the JSON body
and then runs a list of `ApplicationRequestMapperJsonTransformer`s over it, each adding what it knows:
the query string first, then the path parameters (`{id}`). Later transformers overwrite earlier ones, so
a path parameter wins over a query parameter of the same name.

`addRequestJsonTransformer` adds another source; the mapping is a pipeline, not a fixed set of three.

The handler's return value is serialized as the response, and the status code is whatever the route
declared.

### A route written by hand that runs a use case

When a route has to do something `post<T>` does not, like setting a cookie or answering a shape of its own, it
is written by hand and still runs the use case through the application, with its middlewares. An
`ApplicationController` takes what it needs in its constructor: `WebApplicationBuilder` registers the
`ApplicationRequestMapper` and the `WebApplicationExecutor` in the container, and `addController<T>()` builds the
controller from it.

```kotlin
class AuthController(
    private val mapper: ApplicationRequestMapper,
    private val executor: WebApplicationExecutor,
): ApplicationController {
    override fun registerRoutes(http: ApplicationRouteRegister) {
        http.post("/login", ::onLogin)
    }

    private fun onLogin(ctx: Context) {
        val login = mapper.toRequest(Login::class, ctx)
        val response = executor.execute(login, ctx)

        if (response.isSuccessful) ctx.cookie(Cookie("sessionToken", response.sessionToken!!, isHttpOnly = true))
        ctx.jsonObj("isSuccessful" to response.isSuccessful)
    }
}

app.addController<AuthController>()
```

- `mapper.toRequest(type, ctx)` builds the request as `post<T>` does: the JSON body, then the query string and
  the path parameters.
- `executor.execute(request, ctx)` runs it with the `Context` of the call, which is what the middlewares that read
  the HTTP request need, like `SessionTokenFromHeadersMiddleware`. `executeAsSystem(request, ctx)` runs it as the
  system instead of the caller.
- The answer is whatever the handler writes to `ctx`; `jsonValue` and `jsonObj` from `ContextExtensions` write JSON.
  An exception goes to the error handlers, as in any route.
- `executor.identityOf(ctx)` says who is asking in the call, as the middlewares of the application tell it, without a
  use case of its own.

The `ApplicationRouteRegister` an `ApplicationController` is handed has them too, as `http.mapper` and
`http.executor` (and the serializer as `http.mapper.serializer`), for what builds on routes that run use cases,
like the MCP endpoint of `trantor-mcp-server`.

---

## Errors

An `HttpErrorHandler<T>` turns one exception type into a response:

```kotlin
class OrderNotFoundHandler: BaseJsonErrorHandler<OrderNotFound>() {
    override val errorType = OrderNotFound::class.java
    override val status = 404
}
```

`BaseJsonErrorHandler` sets the status, logs, and writes the error as JSON. The handlers that ship map
the domain errors of `trantor-domain`:

| Handler | Status |
|---|---|
| `BadRequestErrorHandler` | 400 |
| `NotAuthenticatedErrorHandler` | 401 |
| `ForbiddenErrorHandler` | 403 |
| `NotFoundErrorHandler` | 404 |
| `InternalErrorHandler` | 500 |

`InternalErrorHandler` is the one that catches what nothing else did: it logs at error level and does not
leak the message.

---

## Auth

`SessionTokenFromHeadersMiddleware` takes the session token off the request headers and puts it on the
`ExecutionContext`, so a use case sees who is asking without knowing about HTTP.

---

## Websockets and broadcasting

This module implements the `Broadcaster` that `trantor-core` declares.

- `SessionManager` holds the connected clients, `ChannelRegistry` which channels each is on.
- `WebSocketHandler` handles the socket itself; `DefaultClientSession` is one client.
- `DefaultBroadcaster` sends a message to a channel, which reaches every session subscribed to it.

```kotlin
services.addBroadcaster()

broadcaster.send("orders", OrderPlaced(...))
```

Messages on the wire are `WebSocketMessage` — `WebSocketEventMessage` for application events,
`WebSocketInternalMessage` for subscribe/unsubscribe, `WebSocketErrorMessage` for failures.

---

## Tests

The unit tests cover the request mapper and its transformers, the error handlers, `ContextExtensions`,
the session-token middleware and the broadcast stack. Two end-to-end suites are tagged `slow` and start a
real server on a free port:

- `HttpServerTest` — routing, filters, correlation ids, error handlers
- `WebApplicationTest` — a route declared as the use case it runs, end to end

```bash
./gradlew :trantor-web:slowTest
```

Still untested: `JavalinRouteRegister`, `DefaultHttpRequestLogger`, `HttpServerStats` and the websocket
protocol inside `WebSocketHandler` (join, leave, ping, and the error messages), which needs a real socket
or a seam of its own.
