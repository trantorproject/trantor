# Conventions

What the code in this repository looks like, and why. [AGENTS.md](../AGENTS.md) has the short version;
this is the reference.

The mechanical part is in `.editorconfig` and IntelliJ applies it. What follows is what a formatter
cannot enforce.

---

## The three goals

Every rule below is downstream of these:

- **Explicitness over magic.** No classpath scanning, no annotation-driven wiring, no behaviour that
  appears because a jar is present. An application says what it wants. If a reader has to know a
  framework rule to predict what happens, the design is wrong.
- **Simplicity.** The smallest thing that does the job. A new abstraction earns its place by removing
  more than it adds.
- **Pragmatism.** Industry practice over invention. Clean architecture, simple design, pragmatic DDD.

---

## Formatting

Enforced by `.editorconfig`:

- 4 spaces, never tabs. 120 columns.
- Trailing commas in multi-line parameter and argument lists.
- Explicit imports until five names from the same package.
- `class Foo: Bar` and `val name: String` — no space before a colon, one space after.

Not enforced, still expected:

- Expression bodies when the function is a single expression:

  ```kotlin
  override fun sum(a: Int, b: Int) = a + b
  fun getDefaultQueue() = getQueue()
  ```

- A blank line between logical blocks inside a function, and none right after a class header or before a
  closing brace.
- Early return over nesting. `if (...) return@apply` reads better than wrapping the rest in an `else`.

---

## Naming

**Errors end in `Error`**, not `Exception`, and they are `open` so an application can specialize them:

```kotlin
open class DomainError(message: String, cause: Throwable? = null): Exception(message, cause)
open class NotFoundError(message: String = "Not found", cause: Throwable? = null): DomainError(message, cause)
```

An error carries what a caller needs to act on, as properties, not buried in the message:

```kotlin
class RequiredConfigError(val path: String): Exception("Missing required config $path")
```

The message says what is wrong **and what to do about it** when that is knowable:

```kotlin
throw AuthenticationError(
    provider,
    "There is no OpenAI api key. Set the OPENAI_API_KEY environment variable, " +
        "or ai.providers.openai.apiKey in the configuration.",
)
```

**Sets of constants are plural**: `ServiceLifetimes`, `HttpMethods`, `FinishReasons`.

**Prefixes follow reach.** A name shared across a family of things carries no prefix; a name that belongs
to one family carries it.

```
CallOptions, Usage, ResponseInfo        shared by every model family
ChatModel, ChatRequest, ChatResponse    specific to chat
JdbcSettings, HttpServerSettings        specific to one integration
```

**Acronyms keep their case**: `AIProvider`, `OpenAIConfig`, `SqsQueue` — not `AiProvider`.

**Defaults are named `Default...`** when they are the implementation the framework registers
(`DefaultServiceProvider`, `DefaultEventDispatcher`), and `Null...` when they are the do-nothing one
(`NullBroadcaster`, `NullTransactionManager`, `NullScheduler`). A `Null...` implementation is how a
feature stays optional without nullable plumbing everywhere.

**Extension files** are named after what they extend, in plural: `ServiceRegistryExtensions.kt`,
`ContextExtensions.kt`, `StringExtensions.kt`.

---

## Settings classes

A settings class is a `data class`, every property is `var`, and every property has a default:

```kotlin
data class JdbcSettings(
    var url: String = "",
    var username: String = "",
    var password: String = "",
)
```

- `var`, because `configure<T>` adjusts the instance after it is built.
- Defaults, because `addConfig` deserializes `{}` when the section is missing, and the Gson adapter
  (`KotlinReflectiveTypeAdapterFactory`) calls the primary constructor with `callBy`, skipping optional
  parameters. Kotlin defaults are therefore the real defaults and must not be duplicated elsewhere.
- A value that can be absent is **nullable**, not a sentinel. `HttpServerSettings.managementPort = -1`
  predates the rule; new settings use `null`, because "the config said nothing" is a different fact from
  "the config said zero".

A settings class may also carry behaviour as a lambda property with a working default, which is how an
application overrides a piece of a component without subclassing it:

```kotlin
var requestLoggerFactory: (logger: Logger) -> HttpRequestLogger = { DefaultHttpRequestLogger(it) },
var configureJavalin: (config: JavalinConfig) -> Unit = {},
```

---

## Registration

The four patterns are in [AGENTS.md](../AGENTS.md#registration-the-patterns) and the canonical example is
`trantor-data/src/dev/botta/trantor/data/jdbc/ServiceRegistryExtensions.kt`. Two rules that are easy to
violate by accident:

**`getAll<T>()` instantiates everything it finds.** It is for iterating things the application deliberately
registered, never for discovering optional collaborators. A provider nobody asked for must cost nothing —
if registering it by default breaks an application that has no credentials for it, the design is wrong, and
the fix is to make the credential lazy, not the provider.

**Providers register themselves; behaviour is named by the application.** A new backend for something
(a queue driver, a model provider) may add itself to its registry through `configure<Registry>`. A
middleware, interceptor or filter may not: order matters and silence is worse than repetition, so the
application lists them.

---

## Comments and KDoc

**No comment restates the code.** `// increment the counter` above `counter++` is noise that rots.

A comment explains **why**, and is worth writing when the reason is not recoverable from the code:

```kotlin
// Spreads out the retries of everyone who got rate limited at the same moment
private val jitter: Double = 0.2,
```

```kotlin
// A model that does not wrap what the network threw at it still deserves another try
is IOException -> true
```

KDoc goes on public declarations and says what a caller cannot read off the signature: what the thing is
for, what it costs, what it does in the case that would surprise them.

```kotlin
/**
 * Ties an http call to a [Cancellation] for as long as the call lasts.
 *
 * It listens before the call is made, because opening it blocks until the provider answers and that wait
 * has to be cancellable too. When the call is over it stops listening, so that a token reused for many
 * calls does not end up holding a callback for each one.
 */
```

Use `[Reference]` links so the IDE resolves them. Do not document parameters that the name already
explains; `@param` earns its place only when there is something to say.

---

## Errors, warnings and the wire

Two rules from `trantor-ai` that generalize to any adapter over something external:

**Never silently drop what you were given.** If a request asks for something the backend cannot do, the
response carries a `ModelWarning` saying so. Losing an instruction quietly is the worst outcome; failing
loudly is second; telling the caller is best.

**Never silently drop what you were sent.** Content that does not map to a known shape comes back as a raw
part carrying the original payload, so a new field from the provider is *visible* rather than lost, and can
be sent back where it came from.
