# Trantor DI (`trantor-di`)

This guide explains the actual runtime behavior of Trantor's dependency injection module (inspired by .NET Core DI), so a coding agent can use it without making incorrect assumptions.

---

## Mental Model

Trantor DI is split into two parts:
- `ServiceRegistry`: declares services (descriptors).
- `ServiceProvider` (`DefaultServiceProvider`): resolves instances from the registry.

There is no classpath scanning or auto-registration. Everything is explicit.

---

## Main Components

- `ServiceRegistry`
  - Stores `ServiceDescriptor` entries.
  - Supports `Transient`, `Singleton`, and `Scoped`.
  - Supports keyed services (`key: String?`) for multiple implementations of the same type.
  - Supports post-creation configuration callbacks (`configure`).
- `ServiceDescriptor`
  - Describes how to build an implementation: `implementationType`, `implementationFactory`, or `instance`.
  - Has `serviceId = serviceType + key`.
  - Has `implementationId` (type/factory/instance + key), used for singleton/scoped caches.
- `DefaultServiceProvider`
  - Resolves services with `get`, `tryGet`, `getAll`, and `getOrDefault`.
  - Creates objects with `create(Class)` / `create(KClass)` using constructor injection.
  - Owns singleton and scoped caches.
- `ServiceValueResolver`
  - Extension point for annotated constructor parameters.
  - Built-in resolvers:
    - `@ConfigValue(path)`
    - `@ServiceKey(key)`
  - Custom resolvers are also discovered from registered `ServiceValueResolver` services.

---

## Service Registration

`ServiceRegistry` exposes:
- `addTransient`, `addScoped`, `addSingleton`
- variants using `implementationType`, `factory`, or `instance` (instance only for singleton)
- `add*IfMissing` variants
- keyed variants (`key`)

Important rules:
- Multiple descriptors can be registered for the same `serviceType` (including the same key).
- `add*IfMissing` checks only `(serviceType, key)`, not implementation identity.
- `ServiceRegistry` auto-registers `Config` as singleton in its constructor (`addSingleton<Config>(config)`).

---

## Resolution Semantics: `get`, `tryGet`, `getAll`
- `get<T>(key?)`
  - Filters descriptors by `(serviceType, key)`.
  - If multiple match, it uses the last registered descriptor (`lastOrNull`).
  - If not found (or scoped service outside a scope), throws `ServiceNotRegisteredError`.
- `tryGet<T>(key?)`
  - Same search as `get`, but returns `null` instead of throwing.
- `getAll<T>(key?)`
  - Returns all descriptors matching `(serviceType, key)`.
  - Preserves registration order.
  - Scoped descriptors outside a scope produce no instance (filtered by `mapNotNull`).

---

## Lifetimes

- `Transient`
  - Creates a new instance every resolution.
- `Singleton`
  - Global cache keyed by `implementationId`.
  - Same declaration => same instance across calls.
- `Scoped`
  - Thread-local cache (`ThreadLocal`) keyed by `implementationId`.
  - Can only resolve inside a scope (`enterScope()` ... `leaveScope()`).
  - `enterScope()` clears current scoped cache and marks scope active.
  - `leaveScope()` clears scoped cache and marks scope inactive.

---

## Creation and Constructor Injection (`create`)

### `create(Class<T>)`

- For Kotlin classes: delegates to `create(KClass<T>)`.
- For Java classes: requires a no-args constructor; otherwise throws `MustHaveDefaultNoArgsConstructorError`.

### `create(KClass<T>)`

Uses the primary constructor and resolves each parameter in order:
1. If the parameter has an annotation handled by a registered `ServiceValueResolver`, use that resolver.
2. Otherwise resolve by type with `tryGet(paramType)`.
3. If unresolved:
   - optional parameter (`default arg`): omit argument so Kotlin uses default value.
   - required parameter: throw `ServiceNotRegisteredError`.

Notes:
- Resolver map is built from built-ins plus custom resolvers. If two resolvers use the same annotation, the last registered resolver for that annotation wins.
- If a resolver returns `ResolvedValue.Skip`, the provider does not set the argument and does not fallback to `tryGet`.
- In practice, `Skip` is intended for optional parameters (with defaults). Using `Skip` on required parameters fails at constructor invocation time.
- There is no explicit circular dependency detection.

---

## Built-in Value Resolvers

### `@ConfigValue(path)`
- Reads from `services.config`.
- Required parameter: uses `config.required(path)` (fails if missing).
- Optional parameter: uses `config[path]` (missing => `Skip`).
- Converts literals to primitive/simple targets:
  - `String`, `Int`, `Long`, `Double`, `Float`, `Boolean`
  - Boolean conversion: case-insensitive `"true"` or `"1"` => `true`.

### `@ServiceKey(key)`
- Forces keyed service resolution by `(parameter type, key)`.
- If missing:
  - optional parameter => `Skip`
  - required parameter => `ServiceNotRegisteredError`

---

## Post-Creation Configuration (`configure`)

`registry.configure<T>(key?) { instance, services -> ... }`

Behavior:
- Runs after instance creation (`createInstance`).
- Applies all configurations matching `serviceType + key`.
- Runs in registration order.
- Lifetime impact:
  - `Transient`: runs on every resolution.
  - `Singleton`: runs once (when singleton is first created).
  - `Scoped`: runs once per scope per descriptor.

---

## `addConfig`

`registry.addConfig<T>("SectionName", key?)` registers a singleton that:
- Gets `JsonSerializer` from DI.
- Reads the config section.
- If section is missing or has no usable JSON, deserializes `"{}"` into `T`.
- Can still be adjusted via `configure<T>` like any other service.

---

## Agent-Oriented Rules

- Prefer `add*IfMissing` for module-level extensibility.
- For multiple implementations of the same type, use `key` + `@ServiceKey`.
- When using scoped services, wrap execution in `enterScope()/leaveScope()`.
- For non-service constructor values, use annotations + `ServiceValueResolver`.
- Remember: `get<T>()` with multiple registrations returns the last one; `getAll<T>()` returns all.

---

## Minimal Example

```kotlin
val registry = ServiceRegistry(configManager)
registry.addSingleton<MyRepo, SqlMyRepo>()
registry.addTransient<MyUseCase, DefaultMyUseCase>()
registry.addSingleton<MyClient>("crm") { sp -> HttpMyClient(sp.get()) }
registry.configure<MyRepo> { repo, _ -> repo.enableCache = true }

val services = DefaultServiceProvider(registry)

val useCase = services.get<MyUseCase>()          // uses last MyUseCase registration
val crmClient = services.get<MyClient>("crm")    // resolves by key
```
