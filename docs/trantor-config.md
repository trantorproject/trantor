# Trantor Config (`trantor-config`)

This guide documents the real behavior of Trantor's configuration system (inspired by .NET Core configuration).

---

## Mental Model

Trantor config is a provider-based key/value system:
- Configuration is flattened into string paths like `section.subSection.key`.
- Multiple providers can be stacked.
- Lookup uses provider precedence: last added provider wins for overlapping keys.
- Paths are case-insensitive.

There is no strongly-typed binding in this module by default; values are stored as `String?`.

---

## Core Types

- `Config` (interface)
  - Read values: `get(path)`, `required(path)`, `has(path)`.
  - Navigate hierarchy: `hasSection(path)`, `getSection(path)`, `getChildren()`.
  - Export: `toJson()`.
- `ConfigManager`
  - Main entry point used by the app.
  - Wraps a `ConfigRoot`.
  - Adds providers via `add(provider)`.
- `ConfigRoot`
  - Holds providers in registration order.
  - Loads each provider when added (`provider.load()`).
  - Resolves values and sections across all providers.
- `ConfigSection` / `DefaultConfigSection`
  - View over a sub-path.
  - Supports relative `get`, `getSection`, `getChildren`, `toJson`.

---

## Resolution Rules

For `config["some.path"]`:

1. Providers are traversed in reverse registration order.
2. First provider containing that exact path is used.
3. If no provider contains it, result is `null`.

Implications:
- Later providers override earlier providers.
- `null` from a later provider overrides non-null from earlier providers.
- `has(path)` is true if any provider contains the path.
- `required(path)` throws `RequiredConfigError` if final value is missing.

The value found is then [interpolated](#interpolation): its references to other keys are resolved.

---

## Interpolation

A value can refer to other keys of the same configuration, and they are resolved when it is read:

```json
{
  "db": { "host": "${DB_HOST:localhost}", "url": "jdbc:postgresql://${db.host}/app" },
  "ai": { "mcp": { "servers": { "github": {
    "url": "https://api.githubcopilot.com/mcp/",
    "headers": { "Authorization": "Bearer ${GITHUB_TOKEN}" }
  } } } }
}
```

- `${path}` is the value of that key, whatever its case, like any other lookup.
- A variable of the environment is a key under its own name (see the
  [environment provider](#environment-variables-provider)), so `${GITHUB_TOKEN}` reads it. That is how a secret
  stays out of a file that is checked in, and also how it reaches what the environment provider cannot name: a
  header like `Authorization` or `X-Api-Key`, or a variable of the environment of a process, whose names a path of
  the provider would turn into camel case.
- `${path:default}` is the default when the key is not there or is `null`. It is taken as it is from the first colon
  on, so `${DATA_DIR:C:\data}` works; a default has no references of its own.
- A key that is not there and has no default is **empty**.
- The value of a reference is resolved too, so `db.url` above ends up with the host of the environment or
  `localhost`. A chain that comes back to where it started (`a -> b -> a`) fails with `ConfigInterpolationError`,
  naming it: none of those keys has a value.
- `$${` is a literal `${`, and a `${` without its `}` is left as it is.
- It happens on every read, including `getSection(...).toJson()`, so a settings class registered with `addConfig`
  gets the values resolved. Being resolved when read, a reference takes what the providers say then: an environment
  variable that overrides `db.host` changes `db.url` too.

---

## Case Sensitivity

Paths are case-insensitive end-to-end:

- Providers based on `ConfigProviderBase` store keys in a case-insensitive map.
- `config["KEY"]` and `config["key"]` are equivalent.
- Children de-duplication is also case-insensitive.

The original key casing from providers can still appear in section keys when enumerating children.

---

## Sections and Hierarchy

`getSection(path)` always returns a section object, even if the section/path does not exist.

Section behavior:
- `section.key`: last token of section path (for `a.b.c`, key is `c`).
- `section.path`: full path used to create the section.
- `section.value`: value at exact section path (can be null).
- `section["child"]`: resolves `section.path + ".child"`.
- `section.getChildren()`: returns immediate child sections.

Note on section checks:

- `hasSection(path)` is prefix-based (`startsWith`) in providers, not strict token-boundary matching.

Children enumeration:
- Built from all provider paths that start with `"$path."` (or all root keys for empty path).
- Returns distinct immediate children (case-insensitive distinct).
- Sorted alphabetically.

---

## JSON Export (`toJson`)

`ConfigSection.toJson()` rules:
- If section has no children, exports scalar JSON value from `section.value` (string or null).
- If section has children, exports a JSON object by default.
- If section is marked as array (`__config_type__ = "array"`), exports JSON array.

Array metadata conventions:
- `__config_type__ = "array"` identifies an array section.
- `size` can be present as metadata.
- `__config_type__` and `size` are excluded from final array items.

`ConfigManager.toJson()` is equivalent to `getSection("").toJson()`.

---

## Provider Contract

All providers implement `ConfigProvider`:

- `load()`: loads data into provider storage.
- `has(path)`, `get(path)`.
- `hasSection(path)`.
- `paths`: all known keys.

Most built-in providers inherit `ConfigProviderBase`, which provides:

- case-insensitive path storage
- default `has`, `get`, `hasSection`

---

## Built-in Providers

### Memory Provider

`MemoryConfigProvider` stores inline map data and does not load external resources.

Helpers:

- `config.addMemoryCollection("a" to "1", "b" to null)`
- `config.addMemoryCollection(mapOf(...))`

Useful for tests and explicit in-memory overrides.

---

### JSON Resource Provider

`JsonResourceConfigProvider(resourceName)` loads a JSON resource from classpath and flattens it into paths.

Flattening behavior:

- Objects: nested with dot notation (`parent.child`).
- Arrays:
  - marks section with `__config_type__ = "array"`
  - stores `size`
  - stores item paths by index (`arr.0`, `arr.1`, ...)
- Primitive values become strings via JSON value rendering.
- JSON null becomes config null.

If resource is missing or root JSON is not an object, provider does nothing (no exception).

Helper:

- `config.addJsonResource("settings.json")`

---

### Properties Resource Provider

`PropertiesResourceConfigProvider(resourceName)` loads Java `.properties` from classpath.

Behavior:
- Every property key/value is stored as config entry.
- Missing resource does not throw.

Helper:
- `config.addPropertiesResource("settings.properties")`

---

### Environment Variables Provider

`EnvironmentVariablesConfigProvider(prefix = "")` loads OS environment variables from `Env.getAll()`.

Behavior:

- Filters variables by prefix (`startsWith(prefix, ignoreCase = true)`).
- Stores raw key without prefix as one config key.
- If key contains `_`, also stores a normalized key:
  - split by `__` to create path segments (`.`)
  - within each segment, convert `snake_case` to `camelCase`
- Prefix filtering is case-insensitive, but prefix removal uses exact string removal. In practice, use the same prefix casing as the environment variable names.

Example concept:

- `MYAPP_DB__READ_TIMEOUT` (with `prefix = "MYAPP_"`) can produce normalized path like `db.readTimeout`.

Helper:

- `config.addEnvironmentVariables(prefix)`

---

### Command Line Provider

`CommandLineConfigProvider(args)` reads the arguments the application was started with, the way .NET does:

| Argument | Sets |
|---|---|
| `--httpServer.port=9000` | `httpServer.port` = `9000` |
| `httpServer.port=9000` | `httpServer.port` = `9000` |
| `--env staging` | `env` = `staging` (the next argument is the value) |
| `--verbose` (last, or followed by another `--`) | `verbose` = `true` |

- Everything after the first `=` is the value, so `--db.url=jdbc:...?ssl=true` keeps its own `=`.
- An argument with a single dash (`-v`, `-p=9000`), or with no dashes and no `=` (`migrate`), is left alone, so
  the application can still have commands and short options of its own.
- When a key is given twice, the last one counts.

Helper:

- `config.addCommandLine(args)`

The host builder adds it for the `args` it was given (`Application.builder(args)`), last, so a flag at startup
overrides any file or environment variable: `java -jar app.jar --env=staging --httpServer.port=9000`.

---

## Precedence Pattern (Equivalent to .NET-style layering)

Typical order:

1. Base settings (`settings.json`)
2. Environment-specific settings (`settings.production.json`)
3. Environment variables
4. Command line arguments
5. In-memory overrides for tests

Since the last provider wins, add providers from lowest to highest priority.

---

## Error Semantics

- `required(path)` throws `RequiredConfigError("Missing required config <path>")`.
- Missing sections do not throw; they return empty section views.
- Missing JSON/properties resources do not throw in built-in resource providers.
- A reference to a key that is not there reads as empty; a chain of references that comes back to where it started
  throws `ConfigInterpolationError`.

---

## Agent-Oriented Rules

- Always assume config values are strings unless converted by consuming code.
- Keep secrets out of checked-in files with a reference to the environment: `"Bearer ${GITHUB_TOKEN}"`.
- Use provider order intentionally; override behavior depends only on add order.
- Use `getSection(...).toJson()` when you need structured payloads from flattened keys.
- For arrays represented in config, preserve `__config_type__ = "array"` markers.
- Prefer `addMemoryCollection` for deterministic tests and temporary overrides.

---

## Minimal Example

```kotlin
val config = ConfigManager()
    .addJsonResource("settings.json")
    .addJsonResource("settings.production.json")
    .addEnvironmentVariables("MYAPP_")
    .addMemoryCollection("featureFlags.newCheckout" to "true")

val dbHost = config.required("database.host")
val timeout = config["http.client.timeout"]
val apiSectionJson = config.getSection("api").toJson()
```
