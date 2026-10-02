# trantor-core

The application services most Trantor applications use. It sits on `trantor-hosting`, so everything here
is registered in the container and started with the host.

---

## Application

`Application` is a `Host` that also executes use cases. It wraps a host and an `ApplicationExecutor`,
which is a CQRS-style bus (`dev.botta:cqbus`): a request goes in, a handler runs it, a response comes out.

```kotlin
val app = Application.builder(args).build()

app.registerMiddleware(LoggingMiddleware())
app.registerMiddleware(ValidationMiddleware(), MiddlewarePriorities.High)

val result = app.execute(PlaceOrder(customerId, items))
```

**Handlers** are registered with `addHandler`, on an `Application` or a `WebApplication`:

```kotlin
class PlaceOrderHandler(private val orders: OrderRepository): RequestHandler<PlaceOrder, Order> { ... }

app.addHandler<PlaceOrderHandler>()
app.addHandler(RequestHandler<GetOrder, Order> { request, _ -> ... })
```

- `addHandler<H>()` handles the request the declaration of `H` names (`RequestHandler<PlaceOrder, Order>`
  handles `PlaceOrder`, also when it comes from a base class), and the container builds one for every request,
  so the constructor gets its dependencies, the scoped services of that request included.
- A `ContextAwareRequestHandler` is registered the same way, and gets the whole `ExecutionContext`.
- `addHandler(handler)` takes an instance, which handles every request of its type.
- A class that is not a handler, or whose request is a type parameter, fails when it is registered, at startup,
  not when the first request arrives.
- Registering a second handler for a request replaces the first, as `CQBus` does.

`ExecutionContext` carries who is asking. `executeAsSystem(request)` runs one with `SystemIdentity`, for
work that has no user behind it — a job, a scheduled task, a migration.

**Middlewares are registered explicitly, in order.** Nothing is collected from the container. The priority
decides where a middleware lands relative to the others, and the application decides the rest.

`ApplicationBuilder` / `ApplicationBuilderConfig` are the `Host` equivalents with the executor added.

---

## Events

An `Event` is something that happened (`trantor-primitives`). `EventDispatcher.publish(event)` hands it to
every `EventHandler` subscribed to its type.

```kotlin
class SendReceipt: EventHandler {
    override val eventTypes = listOf(OrderPlaced::class)

    override fun on(event: Event) { ... }
}
```

Two things a handler decides for itself:

- `afterCommit` (default `true`): the handler runs after the transaction commits, so it never reacts to a
  change that was rolled back.
- `queued`: extend `QueuedEventHandler(queueName, delaySeconds)` and the handler runs as a job instead of
  in line.

**An event type is a name, not a class.** `@EventType("order.placed.v2")` fixes the name so the class can
be renamed or moved without breaking what is already in a queue. Without it the simple class name is used.
`DefaultEventSerializer` refuses two classes with the same type, because the alternative is deserializing
into the wrong one.

`EventsModule` registers the defaults. `NullEventDispatcher` is the do-nothing one.

---

## Jobs

A `Job` is work to run later; a `JobHandler<T>` runs it. `JobDispatcher.dispatch(job)` serializes it and
puts it on a queue.

```kotlin
services.addModule<JobsModule>()
services.addJobProcessor(queueName = "emails", maxConcurrentWorkers = 4)
```

- `JobQueueRegistry` holds the queues. Drivers register themselves (`addQueueDriver("sqs", ...)`) and the
  queues come from `jobs.queues` in configuration. The first queue added, or `jobs.queues.default`, is
  the default.
- `JobHandlerRegistry` maps a job class to its handler. Registering two for one job is an error.
- `JobProcessor` is a `HostedService`: it polls a queue and runs what it finds.
- `@JobType("...")` does for jobs what `@EventType` does for events, and for the same reason.

**Dispatch waits for the commit** (`jobs.afterCommit`, default `true`): a job enqueued inside a
transaction is only pushed once that transaction commits, so a rolled back change never produces work.

**A job goes on in the trace of whoever dispatched it.** It carries the correlation id and, when there is an
`OpenTelemetry` in the container, the trace: sending it is a `send {queue}` span and running it a
`process {queue}` span, child of the first. See [trantor-opentelemetry](trantor-opentelemetry.md).

---

## Queues

`MessageQueue` is the abstraction — enqueue, poll, delete, clear, size, and the `system` it runs on, as
OpenTelemetry names it (`aws_sqs`). A `QueueFactory` builds one from the section of configuration it is declared
in, which is how a driver plugs in. Two ship: `memory`, here, which `JobsModule` registers, and `sqs` in
`trantor-queues-sqs`.

A `Message` is a type, a body, the correlation id and the trace context of whoever sent it. A driver stores the
whole message, so both travel without the driver knowing.

```json
{
  "jobs": {
    "queues": {
      "default": "emails",
      "emails": { "driver": "sqs", "name": "app-emails-production", "pollVisibilityTimeout": 300 }
    }
  },
  "aws": { "region": "sa-east-1" }
}
```

Each key of `jobs.queues` is a queue, and its section is everything the driver gets: the `driver`, the `name` of the
queue where it lives (the key, when it says none) and the settings of the driver. The application asks for it by
the key.

**The `sqs` driver** (`services.addSqsQueue()`) reads `SqsQueueSettings` from that section: `region`,
`endpointOverride`, `pollMaxMessages` (10), `pollWaitTimeSeconds` (20) and `pollVisibilityTimeout` (60). The region
and the endpoint are those of `aws` when the queue says none, so they are written once for all the queues.

**The `memory` driver** keeps the queue in the process, for development, for tests, and for an application that
runs as a single instance and can afford to lose what is pending when it stops:

```json
{ "jobs": { "queues": { "emails": { "driver": "memory", "pollVisibilityTimeout": 300 } } } }
```

- It behaves like SQS where a job can tell: a poll waits for a message (`pollWaitTimeSeconds`, 20, and an enqueue
  wakes it at once), a message being handled is hidden for `pollVisibilityTimeout` seconds (60) and comes back as a
  retry unless it was deleted, and `delaySeconds` holds one back. A poll takes up to `pollMaxMessages` (10).
- Its settings have the names of those of `sqs`, so a queue can change driver and keep them. Group and
  deduplication ids are ignored.
- What it holds dies with the process, and each instance of an application has its own: a job dispatched on one
  is only run there. More than one instance, or work that must survive a restart, needs a real queue.

---

## Cache

`InMemoryCache<K, V>` is a two-level cache and the subtlest thing in this module:

- **L2** is a Caffeine cache shared by everyone, with `expireAfter` and `maximumSize`.
- **L1** is a per-thread map that only exists while a transaction is open, cleared when it closes.

Outside a transaction, reads and writes go straight to L2. Inside one, a write lands in L1 and only
reaches L2 **after the commit** — so a rolled back transaction leaves the shared cache untouched, and a
concurrent request never sees a value that was never saved.

```kotlin
val cache = cacheFactory.create<OrderId, OrderSnapshot>(InMemoryCacheSettings(expireAfter = 5.minutes))

cache.get(orderId) { loadFromDatabase(it) }
```

> **Always cache snapshots, never mutable entities.** The cache hands out the same object to everyone.

---

## Transactions

`TransactionManager` opens transactions; `Transaction` has `afterCommit`, `afterRollback` and
`afterComplete` hooks, which is what the cache, the job dispatcher and the event dispatcher hang off.

```kotlin
transactionManager.transactional { ... }
```

commits when the block returns and rolls back if it throws. `NullTransactionManager` is the
implementation for applications with no database.

---

## The rest

- **Auth**: `SystemIdentity`, `RolesAuthorization` and `RolesAuthorizationMiddleware`, plus
  `NotAuthenticatedError` and `UnauthorizedAccessError`. `app.identityOf(context)` says who is asking in an
  `ExecutionContext` as the middlewares of the application tell it: it runs the query `CurrentIdentity`, whose
  handler the application registers, through them.
- **Broadcast**: `Broadcaster`, `Channel`, `ClientSession`. The implementation is in `trantor-web` over
  websockets; `NullBroadcaster` is the one for applications that do not broadcast.
- **Scheduling**: `Scheduler` and `ScheduledJob` over db-scheduler, with `NullScheduler` as the default. A
  `ScheduledJob` declares its `schedule` with db-scheduler types (`Schedules.daily(...)`, `FixedDelay.ofMinutes(...)`),
  which is why `trantor-core` exposes db-scheduler to the application.
- **Validation**: `ValidationMiddleware` runs Jakarta Bean Validation on a request before its handler,
  raising `ValidationError`. Besides the standard constraints, `trantor-primitives` has `@NullOrNotBlank` for
  the fields of a partial update, where null means "leave it as it is" and `@NotBlank` would refuse it. It
  lives in `trantor-primitives`, not here, so a request can use it without depending on core.
