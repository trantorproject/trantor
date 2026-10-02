# Trantor

[![Maven Central](https://img.shields.io/maven-central/v/dev.botta.trantor/trantor-bom)](https://central.sonatype.com/artifact/dev.botta.trantor/trantor-bom)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

A backend framework for Kotlin. It takes the good ideas of .NET Core, Laravel and Spring Boot — a host that runs
the application, a service container, layered configuration, use cases behind HTTP routes — and leaves out the
magic.

```kotlin
import dev.botta.cqbus.identity.Identity
import dev.botta.cqbus.requests.Command
import dev.botta.cqbus.requests.handlers.RequestHandler
import dev.botta.trantor.web.application.WebApplication
import dev.botta.trantor.web.application.addHandler

class PlaceOrder(val customer: String, val quantity: Int): Command<Order>

class Order(val id: String, val customer: String, val quantity: Int)

class PlaceOrderHandler: RequestHandler<PlaceOrder, Order> {
    override fun execute(request: PlaceOrder, identity: Identity) =
        Order("order-1", request.customer, request.quantity)
}

fun main(args: Array<String>) {
    val app = WebApplication.builder(args).build()
    app.addHandler<PlaceOrderHandler>()
    app.routes.post<PlaceOrder>("/orders", statusCode = 201)
    app.run()
}
```

## Why Trantor

- **Code you can follow.** What matters is written in your application, not inferred from annotations or from
  scanning the classpath. If a service exists, there is a line that registered it, and you can jump to it.
- **Plain blocking code on virtual threads.** No coroutines and no reactive types: a request, a job or a tool
  call reads top to bottom, and so does its stack trace.
- **A whole application, not only HTTP.** Hosting, services, configuration, a pipeline of use cases with
  middlewares, events, jobs, queues, scheduling, a domain model, data access and the web, made to work together.
- **AI as part of the application.** Models of OpenAI and Anthropic behind one contract, tool loops, agents with
  handoffs, guardrails and approvals, MCP clients and servers — and a use case of the application can be a tool,
  going through the same pipeline, authorization and traces as an HTTP request.

## Requirements

- JDK 25 or newer.
- Kotlin 2.4 or newer. Trantor is a Kotlin framework: its API is built on Kotlin features and is not meant to be
  used from Java.

## Installation

Trantor is published to Maven Central under `dev.botta.trantor`. Import the BOM once, with the version in the badge
above, and add each module without a version:

```kotlin
dependencies {
    implementation(platform("dev.botta.trantor:trantor-bom:0.8.1-beta13"))
    implementation("dev.botta.trantor:trantor-web")

    testImplementation("dev.botta.trantor:trantor-test")
}

kotlin {
    jvmToolchain(25)
}
```

Each module brings the ones it builds on, so you only list the top of what you use.

## Modules

| Module | What it does |
|---|---|
| `trantor-hosting` | The host: application lifecycle, hosted services, modules |
| `trantor-di` | The service container: lifetimes, keys, configuration sections as services |
| `trantor-config` | Layered configuration: files, environment, interpolation |
| `trantor-core` | Use cases and their pipeline, auth, events, jobs, queues, scheduling, cache, transactions |
| `trantor-domain` | Aggregates, ids, domain events and errors, `Money`, `Email` |
| `trantor-data` | JDBC, HikariCP, jOOQ, transactions and the errors of the database |
| `trantor-web` | HTTP server on Javalin: routes, controllers, error handlers, websockets |
| `trantor-web-client` | HTTP client on OkHttp, with streams and SSE |
| `trantor-ai` | Chat models, tool loops, agents, MCP clients, the model catalog and cost estimates |
| `trantor-mcp-server` | An MCP server as a route, exposing use cases as tools |
| `trantor-opentelemetry` | Traces and metrics over OTLP |
| `trantor-gson` | The JSON serializer, with the types of the domain and JSON Schema |
| `trantor-aws`, `trantor-queues-sqs` | AWS integration and the SQS queue driver |
| `trantor-taskpool` | A bounded pool for background work |
| `trantor-test` | Test helpers: AssertJ extensions, Rest-Assured helpers, builders |

## Status

Trantor is still `0.x`: a new version can change the API.

## Documentation

The documentation is at [trantor.nbottarini.com](https://trantor.nbottarini.com).

## Contributing and security

Issues are welcome. Before you open a pull request, open an issue to talk about the change.

To report a vulnerability, use [private vulnerability reporting](https://github.com/trantorproject/trantor/security/advisories/new)
instead of a public issue.

## License

[MIT](LICENSE)
