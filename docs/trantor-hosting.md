# Trantor Hosting (`trantor-hosting`)

This guide documents the actual behavior of Trantor's hosting module (inspired by .NET Core Hosting).

---

## Mental Model

A `Host` is the runtime container of the application. It owns:
- `config` (`Config`)
- `services` (`ServiceProvider`)
- `environment` (`HostEnvironment`)
- `lifetime` (`HostLifetime`)

It also coordinates:
- application lifecycle (`start`, `stop`, `run`)
- module composition and initialization
- hosted services that start/stop with the host

---

## Core Types

- `Host`
  - Runtime abstraction with `start()`, `stop(timeoutSeconds)`, `run()`.
  - Exposes `services`, `config`, `environment`, `lifetime`.
  - Created via `Host.builder(...)`.
- `HostBuilder`
  - Build-time abstraction exposing mutable `config`, `services`, `environment`.
  - Default implementation: `DefaultHostBuilder`.
- `HostBuilderConfig`
  - Builder options:
    - `args`
    - `environmentName`
    - `appName`
    - `config` (custom `ConfigManager`)
    - `disableDefaults`
    - `initializeModules`
- `HostLifetime`
  - Lifecycle events: `onStarted`, `onStopping`, `onStopped`.
  - Control signal: `stopApplication()`.
- `HostedService`
  - Background/runtime service with host-bound lifecycle:
    - `start()`
    - `stop(timeoutSeconds)`
- `Module`
  - Composition unit with two phases:
    - `compose(services, config)` at registration/build time
    - `initialize(services, config)` after host is built

---

## Default Builder Pipeline

`DefaultHostBuilder` executes this flow:
1. Create/resolve `ConfigManager`.
2. Create `ServiceRegistry(config)`.
3. If defaults enabled (`disableDefaults == false`), preload `TRANTOR__` environment variables.
4. Add the command line `args` (always, even with defaults disabled: they are what the application was started
   with, not something it found around). `--env staging` is read here, so it decides which settings file is next.
5. Inject builder config values into config memory, which win over the above:
   - `env` from `environmentName`
   - `appName` from `appName`
6. Build `HostEnvironment` from config:
   - `env` default: `"PRODUCTION"`
   - `appName` default: `"Unnamed App"`
7. Register `HostEnvironment` singleton.
8. If defaults enabled, add default config providers and services:
   - `settings.json`
   - `settings.<env>.json` (env lowercased)
   - `settings.local.json`
   - environment variables (no prefix)
   - environment variables with `TRANTOR__`
   - the command line `args` again, so they win over every file and variable
   - default `HostLifetime` (`DefaultHostLifetime`) if missing

Build result (`build()`):
1. Create `DefaultServiceProvider`.
2. Resolve `HostLifetime` and require it to be `DefaultHostLifetime`.
3. Create `DefaultHost`.
4. Register `Host` as singleton using the created host instance.
5. Validate modules are not registered via factory.
6. If `initializeModules == true`, resolve all `Module` services and run `initialize(...)` once per module class.

---

## Default Host Runtime Lifecycle

`DefaultHost` behavior:
- Constructor registers a JVM shutdown hook that calls `lifetime.stopApplication()`.
- `start()`:
  - idempotent (second call is ignored)
  - logs startup
  - resolves all `HostedService` instances
  - starts hosted services in registration order
  - calls `lifetime.notifyStarted()`
- `stop(timeoutSeconds)`:
  - logs stopping
  - calls `lifetime.notifyStopping()`
  - stops hosted services in reverse order
  - catches/logs individual service stop errors and continues
  - calls `lifetime.notifyStopped()`

---

## `Host.run()` Semantics

`Host.run()`:
1. Calls `start()`.
2. Waits on a latch until lifetime triggers stopping (`onStopping`).
3. Calls `stop()`.

This means the host blocks until a stop is requested (for example through shutdown hook, or explicit `stopApplication()`).

`Host.run { ... }`:
1. Calls `start()`.
2. Executes provided block.
3. Calls `stop()`.
4. Prints stack traces of running `pool-*` threads (debug-oriented behavior).

---

## `DefaultHostLifetime` State Machine

Internal states:
1. `CREATED`
2. `STARTED`
3. `STOPPING`
4. `STOPPED`

Transitions are CAS-protected and one-way:
- `notifyStarted`: `CREATED -> STARTED`
- `notifyStopping`: `STARTED -> STOPPING`
- `notifyStopped`: `STOPPING -> STOPPED`

Handler behavior:
- `onStarted(handler)` executes immediately if host already started/stopping/stopped.
- `onStopping(handler)` executes immediately if host already stopping/stopped.
- `onStopped(handler)` executes immediately if host already stopped.

`stopApplication()` triggers `notifyStopping()` only (it does not call `notifyStopped()` directly).

---

## Module System

`Module` has two responsibilities:
- `compose(services, config)`
  - register services/configuration into container
- `initialize(services, config)`
  - run post-build initialization logic

Registration helpers:
- `services.addModule(moduleInstance)`
- `services.addModule(ModuleClass)`
- `hostBuilder.addModule(...)`

Rules:
- Duplicate module registration is skipped by module concrete class.
- Registration stores module as singleton `Module`.
- `compose(...)` runs immediately at registration time.
- `initialize(...)` runs during `build()` only if `initializeModules == true`.
- During initialize phase, modules are deduplicated by runtime class (`distinctBy { it::class.java }`).
- Modules registered with a factory are explicitly rejected at build time.

---

## Hosted Services

Hosted service registration helpers:
- `addHostedService { ... }`
- `addHostedService(key) { ... }`
- `addHostedService(implementationType, key?)`
- `addHostedService(instance, key?)`

Implementation details:
- Hosted services are registered as singleton `HostedService`.
- All hosted services are resolved at host start (`services.getAll<HostedService>()`).
- Start order: registration order.
- Stop order: reverse registration order.

---

## Defaults and Extensibility

When defaults are enabled:
- default config layering is applied
- default lifetime service is added only if missing (`addSingletonIfMissing<HostLifetime>`)

To fully customize behavior:
- set `disableDefaults = true`
- provide your own config providers and `HostLifetime`

Constraint:
- `build()` currently requires resolved `HostLifetime` to be `DefaultHostLifetime`.
- A different `HostLifetime` implementation will fail unless compatible with that cast requirement.

---

## Environment Model

`HostEnvironment` stores:
- `environmentName` (normalized to uppercase)
- `appName`

Convenience properties:
- `isDevelopment`
- `isStaging`
- `isProduction`

`isEnvironment(name)` compares `name` with the environment, ignoring case, so it also answers for an environment
Trantor does not name (`isEnvironment("qa")`).

The environment is decided before the settings files are read, since it picks `settings.<env>.json`: it comes from
`HostBuilderConfig.environmentName`, the command line (`--env`) or the `TRANTOR__ENV` variable, in that order, and
an `env` in `settings.json` does not change it.

---

## Agent-Oriented Rules

- Add providers/services in builder order intentionally; defaults may override assumptions.
- Register modules via `addModule(...)`, not via manual `Module` factory registrations.
- Keep module side effects split correctly:
  - registration-time logic in `compose`
  - runtime startup logic in `initialize`
- Use hosted services for host-coupled background processes.
- Assume stop is best-effort for hosted services: one failing stop does not prevent others.

---

## Minimal Example

```kotlin
val host = Host.builder {
    appName = "Orders API"
    environmentName = "DEVELOPMENT"
}
    .apply {
        addModule<MyModule>()
        services.addHostedService<MyBackgroundWorker>()
    }
    .build()

host.run()
```

