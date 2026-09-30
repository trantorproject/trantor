# trantor-mcp-server

An MCP server is how an application offers its operations to the models of other applications: an agent in
Claude, in an IDE or in another Trantor application lists the tools and calls them. Here the server is a route of
the application like any other, and a tool is the door to a use case, the way a route that is a use case is: it
runs through the middlewares of the application, which tell who asks, authorize, validate and open the
transaction.

It speaks the 2026-07-28 revision of MCP, where every request stands on its own, and the revisions before it, which
most clients still speak, on the same endpoint and without a session.

```kotlin
dependencies {
    implementation("dev.botta.trantor:trantor-mcp-server")
}
```

It depends on `trantor-ai`, for the protocol and `Tool`, and on `trantor-web`, for the routes and the execution of
use cases.

---

## Declaring it

```kotlin
class StoreMcp(private val products: SearchProductsTool): ApplicationController {
    override fun registerRoutes(http: ApplicationRouteRegister) {
        http.mcp("/mcp", name = "store", version = "1.0.0") {
            instructions("Tools of a hardware store. Prices are in pesos.")
            tool<PlaceOrder>("place_order", "Places an order for a customer")
            tool<GetStock>("stock", "How many units of a product are in stock")
            tool(products)
        }
    }
}

app.addController<StoreMcp>()
```

`app.routes.mcp(...)` does the same without a controller. `name` and `version` are what the server says of itself
to a client, and `instructions` what a client can give its model about the tools as a whole.

The endpoint takes POST, the only method of the 2026-07-28 revision. GET and DELETE answer 405: there is no stream
the server opens on its own and no session to end. A client of that revision says the method and the tool in the
headers too (`Mcp-Method`, `Mcp-Name`), for a gateway in the middle to route by, and a request whose headers do not
say what its body says is turned down with 400.

---

## A use case as a tool

```kotlin
@RolesAuthorization(["seller"])
@Description("An order of a customer")
data class PlaceOrder(
    @Description("The customer who buys") val customer: CustomerId,
    @NotEmpty val lines: List<OrderLine>,
    val payment: Payment,
    val deliverOn: LocalDate,
): Command<OrderPlaced>

http.mcp("/mcp", name = "store", version = "1.0.0") {
    tool<PlaceOrder>("place_order", "Places an order for a customer")
}
```

- **What the model is given** is the JSON Schema the serializer of the application reads for the request, by the
  same rules it reads by: its ids, value objects, hierarchies, `Maybe`, dates and money, with `@Description` and
  the validations of Jakarta. See [The schema of what it reads](trantor-gson.md#the-schema-of-what-it-reads). A
  type the serializer cannot tell the schema of fails when the endpoint is declared, not when a model calls it.
- **What the model sends** is read by that same serializer, as the body of a route is. What it cannot read, like a
  date that is not one, goes back to the model as an error to fix.
- **It runs as a route that is a use case does**, with the HTTP request of the call: the middleware that reads the
  token tells who asks, and the authorization, the validation and the transactions run as they always do.
- **What it answers** is written by the serializer and goes as `structuredContent`, and as text too, for the clients
  that only read that. An object goes as it is; a list or a single value goes under `result`
  (`{"result": [...]}`), since the structured content of MCP is an object. A use case that answers nothing (`Unit`)
  answers "Done.".
- **Whether it only reads** is what a client may use to call it without asking the person: it is `readOnly` when
  given, and otherwise whether the request is a `Query`.

That needs the routes of an `ApplicationController`, or `app.routes`, which know how to run a use case. On the
routes of a plain `Controller`, declaring one fails with a `McpServerError`: only tools written by hand can be
served there.

### What the model reads when it fails

| What happened | What the client gets |
|---|---|
| A validation, a `DomainError`, a permission denied (`UnauthorizedAccessError`), a `ToolError`, arguments that do not fit | A result with `isError` and the message, which the model reads to correct the call or tell the person |
| `NotAuthenticatedError`: nobody said who they are | 401 with `WWW-Authenticate: Bearer`, which makes a client ask the person to sign in |
| A tool that does not exist, or that the caller does not see | The JSON-RPC error -32602, "Unknown tool" |
| Anything else | The JSON-RPC error -32603, "Internal error". The exception goes to the log and to the trace, not to the client: it may say what it should not, like the insides of the application |

---

## A tool written by hand

A tool that is not one use case — it runs several, or asks something outside the application — is a `Tool` of
trantor-ai, the same one an agent of the application uses ([Tools](trantor-ai.md#tools)):

```kotlin
class SearchProductsTool(private val catalog: Catalog): Tool<SearchProductsTool.Args>() {
    override val name = "search_products"
    override val description = "Products of the store whose name contains the text"
    override val readOnly = true

    override fun execute(args: Args, context: ToolContext): ToolResult {
        val call = context.run.require<McpCall>()
        val stock = call.execute(GetStock(args.text))

        return context.json(catalog.search(args.text, onlyInStock = stock.units > 0))
    }

    data class Args(@Description("Part of the name of the product") val text: String)
}

http.mcp("/mcp", name = "store", version = "1.0.0") {
    tool(SearchProductsTool(catalog))
}
```

`McpCall`, in the `RunContext` of the call, runs a use case through the middlewares of the application with the HTTP
request of the call, as a `tool<T>()` does, and `call.identity()` says who is asking. What the tool needs from the
application otherwise comes by constructor. Its args and what it answers are read and written by the serializer of
the application, as those of a use case are; on the routes of a plain `Controller`, by the `GsonSerializer`. A
`ToolError` it throws is a result with `isError`, as in the table above. On the routes of a plain `Controller` there
is no application to run a use case with, and `call.execute` fails with a `McpServerError`.

---

## Who asks, and what they see

```kotlin
http.mcp("/mcp", name = "store", version = "1.0.0") {
    tool<PlaceOrder>("place_order", "Places an order for a customer")
    tool<GetStock>("stock", "How many units of a product are in stock")
    requireAuthentication()
    visibleTools { tools, identity -> if ("seller" in identity.roles) tools else tools.filter { it.readOnly } }
}
```

Who asks is what the middlewares of the application say, as for any route: the endpoint runs `CurrentIdentity` of
trantor-core through them with the HTTP request of the call, so the middleware that reads the token builds the
identity as the application builds it everywhere.

- **`requireAuthentication()`** answers every request of a caller the application did not authenticate with 401
  and `WWW-Authenticate: Bearer`, before anything else, the first one included: that is what makes a client open
  the sign-in. Without it the endpoint is public, and a use case that needs to know who asks can still answer 401.
- **`visibleTools`** says which tools the caller sees, given the whole list and the identity. It is asked once per
  request that lists or calls, so it can read what each one may use from a database in one query. A tool the caller
  does not see does not exist for them: calling it answers that there is no such tool.
- **Hiding a tool is not authorizing it.** It keeps a model from seeing what it cannot use; the use case still
  authorizes, as it does from any route.

Since what each caller sees may differ and the list may change, a listing tells a client of 2026-07-28 not to keep
it (`ttlMs: 0`, `cacheScope: private`).

### A token in the URL

Some clients are given only a URL: a custom connector of Claude without sign-in, for one. There the token goes in the
path, and the middleware of the application that builds the identity reads it from there instead of a header:

```kotlin
http.mcp("/mcp/{token}", name = "store", version = "1.0.0") {
    tool<PlaceOrder>("place_order", "Places an order for a customer")
    requireAuthentication()
}

services.configure<HttpServerSettings> { settings, _ -> settings.secretParams = setOf("token") }
```

`secretParams` keeps the token out of the traces and the logs of the application (see
[Secret params](trantor-web.md#the-server)). A token of its own for each client the person connects, which the
application can revoke without touching the others, is safer than the one of their session.

---

## Clients of before

Most clients still speak 2025-11-25 or earlier, with a handshake (`initialize`) and a session. The endpoint serves
them too, as the TypeScript SDK v2 does with `legacy: 'stateless'`:

- A request that does not say its revision in `_meta` is one of theirs.
- `initialize` answers in the revision the client asked for when it is 2025-11-25, 2025-06-18 or 2025-03-26, and in
  2025-11-25 otherwise, and remembers nothing: each of their requests can be answered on its own as well. There is
  no `Mcp-Session-Id`.
- Their answers say nothing of their type or cache, which those revisions do not have.
- A method the endpoint does not have answers the JSON-RPC error with 200, not 404: a 404 tells a client of before
  that its session is gone, and it would start another.

---

## Telemetry

With an `OpenTelemetry` in the container ([trantor-opentelemetry](trantor-opentelemetry.md)), the endpoint traces
with the one of the server, following the conventions of OpenTelemetry for MCP, in Development like the ones of
GenAI:

- **Each request is a `SERVER` span** named after its method, and after its tool on a call
  (`tools/call place_order`), with `mcp.method.name`, `gen_ai.tool.name`, `jsonrpc.request.id`,
  `mcp.protocol.version`, and the address of the client and the version of HTTP.
- **It goes on from the trace of the client**, which a client sends in the `_meta` of the request; the client of
  trantor-ai does. It has a link to the span of the HTTP request that carried it, since an MCP request and the HTTP
  request under it are not the same thing. Without a trace in `_meta`, it hangs from the HTTP request.
- **It is current while the tool runs**, so the use case and what it does hang from it: an agent of another
  application sees its `execute_tool`, then the call on this server, then the queries of the use case.
- **What failed:** a result with `isError` is `error.type = tool_error`, and does not fail the span. The JSON-RPC
  errors that say the caller asked for what cannot be served (-32700, -32600 — the 401 included —, -32601 and
  -32602) are recorded in `rpc.response.status_code` without failing it either; any other fails it with its
  message, and -32603 keeps the exception.
- **`mcp.server.operation.duration`** measures each request, with the method, the tool, the revision and what
  failed.

The name of a tool goes in the name of the span and in the metric only when it is one of the endpoint: anybody can
send any name, and each made-up one would be a name and a series of its own.

---

## Not yet

- **OAuth**, with the metadata of the protected resource and tokens checked for their audience. Until then the
  credentials are the Bearer token the application already checks, and an MCP client sends it as a header.
- **Answers over SSE**, with progress while a tool runs. Every answer is JSON.
- **`outputSchema`**, the schema of what a tool answers.
- **Resources and prompts**, and telling a client the list of tools changed.
- **Checking `Origin`.** The spec asks every server to turn down a request from a page it does not know, against a
  page that points the browser of the person at a server of their machine or network (DNS rebinding). It was left
  out on purpose: the authentication of the application is what protects a server on the internet, as it does any
  other route.

---

## Tests

- `McpEndpointTest` — the protocol without a server in the middle: discover, listing, calls, the headers and the
  revision, the clients of before and the errors.
- `UseCaseToolTest` — a use case as a tool: its schema, reading what the model sent, what it answers, who asks and
  what each one sees.
- `McpEndpointTracingTest` — the spans and the metric.
- `McpServerTest`, tagged `slow` — the client of trantor-ai talking to an MCP route of a running application, with
  use cases behind the middlewares of the application, and the trace from the client to the use case.

```bash
./gradlew :trantor-mcp-server:allTests
```
