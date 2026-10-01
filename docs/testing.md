# Testing

How tests are written, laid out and run in this repository.

---

## Stack

JUnit 5, AssertJ and MockK. Every module gets them from `trantor-test`, which the root build adds as a
test dependency automatically:

```kotlin
if (project.name != "trantor-test") testImplementation(project(":trantor-test"))
```

So a module's `build.gradle.kts` does **not** declare JUnit, AssertJ or MockK. It only declares what that
module's own tests need on top of them (`testImplementation(project(":trantor-gson"))`, a driver, a fake
server).

`trantor-test` also carries helpers for applications built on Trantor — Rest-Assured extensions,
`PersistentBuilder`, AssertJ extensions. Use them from application tests; framework tests rarely need them.

---

## Layout

```
trantor-x/
  test/            tests, in the same package as what they test
  test_resources/  fixtures
```

`OpenAIChatModel` lives in `src/dev/botta/trantor/ai/providers/openai/`, so its test lives in
`test/dev/botta/trantor/ai/providers/openai/OpenAIChatModelTest.kt`.

When one class has more behaviour than one file can hold without becoming a scroll, split by **topic**,
not by method, and say the topic in the file name:

```
OpenAIChatModelTest.kt                    the core: request, response, errors, cancellation
OpenAIChatModelStreamTest.kt
OpenAIChatModelToolsTest.kt
OpenAIChatModelReasoningTest.kt
OpenAIChatModelStructuredOutputTest.kt
OpenAIChatModelOptionsTest.kt
```

Fakes shared by more than one test file go in a `testing` package inside `test/`
(`test/dev/botta/trantor/ai/testing/FakeHttpClient.kt`). A fake used by exactly one file stays at the
bottom of that file.

---

## Shape of a test file

```kotlin
@file:Suppress("ClassName")

package dev.botta.trantor.ai.models.middleware

import dev.botta.trantor.ai.errors.*
import dev.botta.trantor.ai.models.ModelNotFoundError
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RetryMiddlewareTest {
    @Nested
    inner class `generating` {
        @Test
        fun `tries again after a rate limit`() {
            val model = FlakyChatModel(failures = 2) { RateLimitError("openai") }

            val response = model.with(retry()).generate(ChatRequest("Hola"))

            assertThat(response.text).isEqualTo("ok")
            assertThat(model.attempts).isEqualTo(3)
        }
    }

    // ── helpers and fields at the bottom ──

    private fun retry(maxAttempts: Int = 3) = RetryMiddleware(maxAttempts, sleep = {})

    private class FlakyChatModel(...): ChatModel { ... }

    private val config = ConfigManager()
}
```

Rules in that shape:

- `@file:Suppress("ClassName")` on line one. Backticked class names are the point of it.
- `@Nested inner class` groups behaviour. The group name plus the test name should read as one sentence:
  `generating` + `tries again after a rate limit`.
- **Names describe behaviour, not methods.** `a bad api key is not worth trying again`, not
  `testGenerateThrowsOnAuthError`. A name that mentions the method under test is usually a name that will
  not survive a rename.
- **Given, when, then separated by blank lines, and nothing else.** No `// given` comments — the blank
  lines carry it. A test with one blank line has no arrange step, which is fine.
- **Helpers, fakes and fields at the bottom**, after the tests. What the file is about comes first.
- A helper class the **container** builds cannot be `private`: `ctor.callBy` on a private nested class
  throws `IllegalCallableAccessException`. Leave those as plain `class`.
- One behaviour per test. When an assertion block checks several properties of one result that is still
  one behaviour.

---

## Assertions

AssertJ, always with the reason visible in the failure:

```kotlin
assertThat(response.text).isEqualTo("ok")
assertThat(openAI.organization).isNull()

assertThatThrownBy { models().chat() }
    .isInstanceOf(ModelNotFoundError::class.java)
    .hasMessageContaining("There is no provider called anthropic")
```

Assert on the message when the message is the feature. An error that is supposed to tell the user what to
do is only useful if a test holds it to that.

MockK for collaborators that are awkward to fake (`mockk<ServiceProvider>()`), a hand-written fake when the
fake needs behaviour. Prefer the fake: a fake that records calls reads better than four `verify` blocks.

---

## Time

Trantor reads the clock through `dev.botta.time.Clock`, never `LocalDateTime.now()` directly, so tests can
stop it:

```kotlin
Clock.stoppedAt(LocalDateTime.of(2026, 9, 20, 12, 30))

assertThat(OrderPlaced().occurredAt).isEqualTo(LocalDateTime.of(2026, 9, 20, 12, 30))
```

`Clock` is a global object, so a test that stops it **must** put it back:

```kotlin
@AfterEach
fun letTheClockRunAgain() {
    Clock.live()
}
```

Never write a test that passes because a real duration elapsed. Inject the waiting instead — `RetryMiddleware`
takes `sleep: (Duration) -> Unit` for that reason, and tests pass `{}`.

---

## Fixtures

`test_resources/`, organized by what they belong to (`test_resources/openai/text-simple.json`).

**A fixture of an external wire format is a real recording, not a guess.** The OpenAI fixtures in
`trantor-ai` were captured from live calls with a recording decorator around the HTTP client. A
hand-written fixture encodes what someone *believed* the format was, and a test built on it passes while
production fails.

When a recording is genuinely unavailable — an error path that is hard to trigger — write the fixture by
hand and **say so in a comment at the point of use**, so the next reader knows which facts are verified.

---

## Running

```bash
./gradlew build
```

```bash
./gradlew :trantor-ai:test
```

```bash
./gradlew :trantor-ai:test --tests "*RetryMiddlewareTest*"
```

Tests tagged `slow` are **excluded** from `test`:

```kotlin
@Tag("slow")
class HttpServerTest { ... }
```

```bash
./gradlew slowTest
```

```bash
./gradlew allTests
```

Tag a test `slow` when it starts a real server, hits a network, or waits on a clock.

> `slowTest` and `allTests` come from the conventions plugin (`dev.botta.kotlin-conventions`). Versions
> before 0.4.3 registered them without the test classpath and let `excludeTags("slow")` leak into both, so a
> test tagged `slow` ran in **no** task at all, which looks exactly like a test that passes. After tagging
> one, run `slowTest` and check the count.

---

## Testing an application against a database

For applications built on Trantor. The usual shape: a test database migrated by the build, and every test
wrapped in a transaction that is rolled back at the end, so tests never see each other's data.

**Use `addSimpleJdbc()`**, registered before the application's modules. Its `SimpleJdbcTransactionManager`
keeps one transaction for every thread, and `TransactionAwareDataSource` hands its connection to whoever asks.
The test opens the transaction; the use cases' own transactions nest inside it as savepoints; the HTTP
server's threads see what the test wrote. `addJdbc()` is thread-local, so the server would not see the
scenario and the rollback would not undo what the requests wrote.

```kotlin
val builder = WebApplication.builder { appName = "orders-test" }
builder.config.addMemoryCollection("jdbc.url" to testDbUrl, "httpServer.port" to "9191")
builder.services.addSimpleJdbc()
builder.services.addSingleton<EventDispatcher>(events)          // FakeEventDispatcher
builder.services.addSingleton<JobDispatcher>(jobs)              // FakeJobDispatcher
builder.services.addModule<OrdersModule>()
builder.services.addSingleton<Scheduler, NullScheduler>()       // after the module that adds the scheduler
val app = builder.build()

val transaction = app.services.get<TransactionManager>().beginTransaction()
app.start()
// ... the test ...
app.stop()
transaction.rollback()
```

- `addModule` composes the module right away and the last registration wins, so a service registered **after**
  the module replaces the module's one. Services the framework adds with `addSingletonIfMissing`
  (`EventDispatcher`, `JobDispatcher`) can be registered before.
- **Replace the scheduler.** `DefaultScheduler` polls the database from its own thread, and in this setup that
  is the test's connection.
- **Replace the dispatchers.** `FakeEventDispatcher` and `FakeJobDispatcher` (`trantor-test`) record what was
  published or dispatched, so a test can assert it, and nothing reaches a queue.
- **Event handlers that run after commit never run** in these tests: they wait for the outermost transaction,
  and it is rolled back. Test those handlers by calling them directly.
- Begin the transaction before building the scenario, or the scenario is committed and outlives the test.

---

## Verifying that a test actually runs

HTML and XML reports are disabled by the conventions plugin, so **a passing run prints nothing**. There is
no summary line to read, and a test that silently did not run — a `@Nested` class that was not `inner`, a
file in the wrong source set — looks exactly like a test that passed.

There is an init script in the repository for exactly this:

```bash
./gradlew :trantor-primitives:test --rerun-tasks -I gradle/test-summary.gradle
```

```
>>> trantor-primitives:test - 59 tests, 0 failed, 0 skipped
```

`--rerun-tasks` matters: an up-to-date task executes nothing and reports zero.

After writing a batch of tests, run that and check the count is what you expect. When a test asserts
something subtle, also break its assertion once and watch it fail with the message you expect — a test that
passes for the wrong reason is worse than no test.

---

## What to test

Everything with a decision in it. In practice, in order of value:

1. **Behaviour at a boundary** — what the adapter sends, what it does with what comes back, what it does
   when what comes back is wrong. This is where the bugs are.
2. **Error messages that are meant to help.** If the message names an environment variable, a test asserts
   that it does.
3. **The surprising case** the KDoc bothered to describe. If the doc says a token reused across calls does
   not leak callbacks, there is a test that reuses a token across calls.
4. **Pure functions.** Cheap, fast, and they document the shape of the thing.

What not to test: getters, `data class` equality, delegation that adds nothing, and the framework's own
dependencies.
