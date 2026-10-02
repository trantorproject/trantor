<p align="center">
  <a href="https://trantor.nbottarini.com">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="brand/svg/trantor-logo-horizontal-dark.svg">
      <img alt="Trantor" src="brand/svg/trantor-logo-horizontal-light.svg" width="360">
    </picture>
  </a>
</p>

<h3 align="center">Sustainable code for the agentic era.</h3>

<p align="center">
  A batteries-included Kotlin framework with no magic, so the code you and your agents write stays easy to change.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/dev.botta.trantor/trantor-bom"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/dev.botta.trantor/trantor-bom?color=A47AE4"></a>
  <a href="https://github.com/trantorproject/trantor/actions/workflows/main.yml"><img alt="Build" src="https://github.com/trantorproject/trantor/actions/workflows/main.yml/badge.svg?branch=main"></a>
  <a href="https://kotlinlang.org"><img alt="Kotlin 2.4" src="https://img.shields.io/badge/kotlin-2.4-7F52FF?logo=kotlin&amp;logoColor=white"></a>
  <a href="https://openjdk.org/projects/jdk/25/"><img alt="JDK 25" src="https://img.shields.io/badge/jdk-25-437291?logo=openjdk&amp;logoColor=white"></a>
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/license-MIT-blue"></a>
  <a href="https://trantor.nbottarini.com"><img alt="Docs" src="https://img.shields.io/badge/docs-trantor.nbottarini.com-A47AE4"></a>
</p>

<p align="center">
  <a href="https://trantor.nbottarini.com/getting-started/quickstart/">Quickstart</a> ·
  <a href="https://trantor.nbottarini.com">Documentation</a> ·
  <a href="CHANGELOG.md">Changelog</a>
</p>

---

Trantor is a backend framework for Kotlin. It takes the good ideas of .NET Core, Laravel and Spring Boot — a host
that runs the application, a service container, layered configuration, use cases behind HTTP routes — and leaves
out the magic.

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
- **Practices that hold up over time.** Use cases, domain building blocks and dependencies that point one way, so
  an application stays as easy to change in its second year as in its first week.
- **Ready for coding agents.** The repository has an `AGENTS.md` and docs written for agents, and the code has
  nothing implicit to misread: what an agent can follow, it can extend the same way you would.

## Requirements

- JDK 25 or newer.
- Kotlin 2.4 or newer. Trantor is a Kotlin framework: its API is built on Kotlin features and is not meant to be
  used from Java.

## Installation

Trantor is published to Maven Central under `dev.botta.trantor`. Import the BOM once, with the version in the badge
above, and add each module without a version:

```kotlin
dependencies {
    implementation(platform("dev.botta.trantor:trantor-bom:0.9.0"))
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

## Brand

The logo, its variants, the favicons and the colors are in [brand/](brand/README.md).

## License

[MIT](LICENSE)
