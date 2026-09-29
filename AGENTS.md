# Trantor

A backend framework for Kotlin, built by Nicolas Bottarini. Inspired by .NET Core, Laravel and Spring Boot,
but explicitly not by their magic: **behaviour that matters is written in the application, not inferred from
annotations or classpath scanning**.

Three goals govern every decision here: **explicitness over magic, simplicity, pragmatism**. When in doubt,
pick the option a reader can follow without knowing the framework.

## Before you write code

Read the module you are about to depend on. Not the module next to yours — the one you are calling.
Most of what looks missing already exists:

| If you need to | Read first |
|---|---|
| Register services, read a config section, configure an instance after creation | [docs/trantor-di.md](docs/trantor-di.md) and `trantor-di/src/.../ServiceRegistry.kt` |
| Read configuration | [docs/trantor-config.md](docs/trantor-config.md) |
| Start something with the application | [docs/trantor-hosting.md](docs/trantor-hosting.md) |
| Model a domain: aggregates, ids, events, domain errors, `fail`/`Ensure` | [docs/trantor-domain.md](docs/trantor-domain.md) |
| Add a module of your own | [docs/architecture.md](docs/architecture.md), then the table below |
| Write tests | [docs/testing.md](docs/testing.md) |
| Match the house style | [docs/conventions.md](docs/conventions.md) |

`ServiceRegistry` is the single most misread file in the repo. Skim its public functions once before
designing any registration, and copy `trantor-data/src/dev/botta/trantor/data/jdbc/ServiceRegistryExtensions.kt`,
which is the canonical example.

## Modules

| Module | What lives here |
|---|---|
| `trantor-primitives` | The bottom of the stack: logging, small Kotlin extensions, event and serialization abstractions, the cancellation token. Depends on nothing of Trantor. |
| `trantor-di` | The service container: `ServiceRegistry`, `ServiceProvider`, lifetimes, value resolvers. |
| `trantor-config` | Stacked configuration providers, sections, case-insensitive paths. |
| `trantor-hosting` | `Host`, `HostBuilder`, `HostedService`, `Module`. Application lifecycle. |
| `trantor-opentelemetry` | `addOpenTelemetry()`: the OpenTelemetry SDK and the OTLP exporters, for traces and metrics. Trantor traces and measures with the API, which is in primitives. |
| `trantor-core` | Application services: application pipeline, auth, broadcast, cache, events, jobs, queues, scheduling, transactions. |
| `trantor-domain` | Domain building blocks: `Aggregate`, `Id`, `Money`, `Email`, domain errors, `Ensure`. |
| `trantor-data` | JDBC, Hikari, jOOQ, transaction managers, the errors of the database. |
| `trantor-web` | HTTP server on Javalin: routes, controllers, error handlers, websockets. |
| `trantor-web-client` | HTTP client abstraction on OkHttp, plus SSE. `addHttpClient()` registers one for the application. |
| `trantor-ai` | LLM access: `ChatModel`, `ModelRegistry`, middlewares, provider adapters. |
| `trantor-mcp-server` | An MCP server as a route of trantor-web, whose tools the models of other applications call. |
| `trantor-gson` | `JsonSerializer` on Gson, with the Kotlin-aware adapters. |
| `trantor-aws`, `trantor-queues-sqs` | AWS integration and the SQS queue driver. |
| `trantor-taskpool` | Bounded pool for background work. |
| `trantor-test` | Test helpers for applications: AssertJ extensions, Rest-Assured helpers, builders. |
| `trantor-bom` | Dependency versions. Not a code module. |

Dependencies point downwards in that table. A module never reaches sideways or up: if `trantor-core` needs
something from `trantor-web`, the abstraction belongs in `trantor-core` and the implementation in `trantor-web`.

## Layout and build

The `dev.botta.kotlin-conventions` plugin remaps the source sets, so this is **not** the Maven layout:

```
trantor-x/
  src/            production code      (package dev.botta.trantor.x)
  resources/      production resources
  test/           tests                (mirrors the package of what it tests)
  test_resources/ fixtures
  generated/      generated code, compiled with src
```

```bash
./gradlew build
```

Tests tagged `slow` are excluded from `test`. Use `./gradlew slowTest` for those and `./gradlew allTests`
for everything. HTML and XML test reports are turned off, so **a successful run prints no summary**: to be
sure a new test actually runs, make it fail on purpose once and watch it fail.

## Registration: the patterns

These four are the whole story. Getting them right is most of what "matching the conventions" means.

### 1. `addX()` extension functions, one file per feature package

Every feature exposes `ServiceRegistry.addX()` in a `ServiceRegistryExtensions.kt` inside its own package.
`addX()` **pulls in what it needs** instead of asking the caller to remember, and is **idempotent**, so
calling it twice or from two places is safe.

```kotlin
fun ServiceRegistry.addJdbc(key: String? = null) = apply {
    if (!has<JdbcSettings>(key)) addJdbcConfig(key)
    if (!has<DataSource>(key)) addHikariCP(key)
    if (!has<TransactionManager>(key)) addJdbcTransactionManager(key)
}
```

### 2. `addConfig<T>(section)` for settings

A settings class is **never** read field by field from `Config`. `addConfig<T>("section")` registers a
singleton that deserializes the whole section with the `JsonSerializer` from the container, and falls back
to `{}` when the section is absent — **which means Kotlin default values are respected**.

```kotlin
fun ServiceRegistry.addOpenAIConfig() = apply {
    if (has<OpenAIConfig>()) return@apply
    addConfig<OpenAIConfig>("ai.providers.openai")
}
```

So a settings class is a data class with `var` properties and a default for every one of them:

```kotlin
data class OpenAIConfig(
    var apiKey: String = Env["OPENAI_API_KEY"] ?: "",
    var baseUrl: String = DEFAULT_BASE_URL,
    var organization: String? = null,
)
```

`var`, because `configure<T>` adjusts the instance after it is built. Defaults, because the config section
may say nothing. Optional values are nullable — never a sentinel.

### 3. `configure<T> { instance, services -> }` to adjust and to self-register

`ServiceConfiguration<T> = (T, ServiceProvider) -> Unit`. Configurations run in registration order, right
after the instance is created (`DefaultServiceProvider.createInstance`), and the registration order of the
`configure` call against the `addSingleton` call does not matter. That is what lets a component **add itself**
to a registry it does not own:

```kotlin
configure<ModelRegistry> { models, services -> models.addProvider(services.get<OpenAIProvider>()) }
```

Accept a `ServiceConfiguration<T>` parameter in `addX()` so an application can adjust settings in code:

```kotlin
fun ServiceRegistry.addOpenAI(configuration: ServiceConfiguration<OpenAIConfig> = { _, _ -> }) = apply {
    addOpenAIConfig()
    configure(configuration)   // before the idempotency guard, so either call order keeps it

    if (has<OpenAIProvider>()) return@apply
    ...
}
```

### 4. Mutable registries for things applications extend

When an application adds entries to something — queues, model providers, channels — the holder is a plain
mutable class with `@Synchronized` mutators and a `loadFromConfig(config, section)`. See
`trantor-core/src/.../jobs/JobQueueRegistry.kt` and `trantor-ai/src/.../models/ModelRegistry.kt`.

Registering a *provider* is automatic; registering *behaviour* (middlewares, interceptors) is **explicit and
ordered**, named by the application, never collected with `getAll<T>()`.

> `getAll<T>()` instantiates every registration of that type. Never use it to discover optional
> collaborators: a provider nobody asked for should cost nothing.

### `Module` for a coherent bundle

`Module` (in `trantor-hosting`) has `compose(services, config)` and `initialize(services, config)`, and
`addModule<T>()` is idempotent. Use it to register a set of defaults that belong together
(`EventsModule`, `JobsModule`). Do not create a module that only wraps one `addX()` call.

## Style

Full detail in [docs/conventions.md](docs/conventions.md). The short version:

- 4 spaces, 120 columns, trailing commas, `.editorconfig` covers the rest.
- `class Foo: Bar`, no space before the colon.
- Expression bodies where the function is one expression.
- Errors are `open class XError(message: String, cause: Throwable? = null): RuntimeException(message, cause)`,
  named `...Error`, carrying the data a caller needs as properties.
- Enum-like sets are named in plural: `ServiceLifetimes`, `HttpMethods`, `FinishReasons`.
- A name that is shared across a family carries no prefix (`CallOptions`, `Usage`); a name specific to one
  family carries it (`ChatRequest`, `JdbcSettings`).
- No comment restates the code. A comment says *why*, or it is not written.

## KDoc

Public API gets KDoc; internals get it when the reason is not obvious from the code. Write what a caller
cannot see from the signature: what the thing is for, what it costs, what it does in the surprising case.
Do not write `/** Gets the name. */`.

Most of the framework has none yet. **If you touch a public declaration that has no KDoc, add it.**

## Tests

See [docs/testing.md](docs/testing.md). The short version:

- JUnit 5 + AssertJ + MockK. TDD: the failing test comes first.
- `@file:Suppress("ClassName")` at the top, `@Nested inner class` for groups, backticked names that read as
  sentences about behaviour, not about methods.
- Given / when / then separated by blank lines, no comments marking them.
- Helper classes, fakes and fields go at the **bottom** of the file.
- Fakes shared between test files live in a `testing` package.
- Fixtures are real recordings whenever the thing under test talks to something real. A hand-written fixture
  is a guess about a wire format; say so in a comment when you have to write one.

## Working agreements

- **Never commit.** Nicolas decides what gets committed and when. Leave the work in the tree and say what
  changed.
- **No credentials in the repo, ever.** Keys come from the environment (`OPENAI_API_KEY`) or from
  configuration that is not checked in.
- Go in small steps. A step ends when it is verifiable, and it gets verified before the next one starts.
- Report what actually happened. If tests fail, show the output; if something was skipped, say so.
