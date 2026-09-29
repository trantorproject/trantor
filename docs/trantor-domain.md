# trantor-domain

The building blocks of a domain model: aggregates and their ids, domain events, domain errors, the `fail` and
`Ensure` helpers that raise them, a repository contract, and two value objects (`Email`, `Money`).

It depends only on `trantor-primitives`. Nothing here knows about HTTP, databases or the container, so a domain
model written with it stays a plain Kotlin model.

---

## Aggregates and ids

An `Aggregate<ID>` is an entity with an identity and a list of the domain events it has recorded:

```kotlin
class Order(id: OrderId, val customerId: CustomerId): Aggregate<OrderId>(id) {
    var status = OrderStatuses.Open
        private set

    fun cancel() {
        if (status == OrderStatuses.Cancelled) return
        status = OrderStatuses.Cancelled
        recordedEvents.trigger(OrderCancelled(id))
    }
}
```

- Two aggregates are **equal when they are the same class with the same id**, whatever their state.
- `toString()` is `Order(<id>)`. Override it with `describe(...)` when more context helps a log.
- `id` has a protected setter, for the rare subclass that assigns it after construction.

An `Id` wraps a UUID. The default is a **UUID v7** (`UuidCreator.getTimeOrderedEpoch()`), so ids sort by creation
time. Every aggregate gets its own id class, which is what makes an `OrderId` and a `CustomerId` impossible to mix
up:

```kotlin
class OrderId(rawId: UUID = UuidCreator.getTimeOrderedEpoch()): Id(rawId) {
    constructor(raw: String): this(UUID.fromString(raw))
}
```

- Ids are equal only when they are **the same class** with the same UUID. An `OrderId` never equals a
  `CustomerId`, nor the bare `UUID` inside it.
- `toString()` is the UUID; `toUUID()` gives it back for whatever stores it.
- A string that is not a UUID is refused when the id is built.

`trantor-gson` serializes any `Id` as its UUID string, so ids travel over the wire without extra configuration.

---

## Domain events

A `DomainEvent` is something that happened in the domain. It is an `Event` (`trantor-primitives`), so it has an
`id` (UUID v7), an `occurredAt` taken from `Clock.now()` and an `eventType` (the simple class name, or
`@EventType("...")`).

```kotlin
data class OrderCancelled(val orderId: OrderId): DomainEvent()
```

An aggregate does not publish events; it **records** them in `recordedEvents`, and whoever persists the aggregate
publishes them:

| Method | What it does |
|---|---|
| `trigger(event)` / `triggerAll(events)` | Records events |
| `consume()` | Returns the recorded events and forgets them |
| `clear()` | Forgets them without returning them |

`RecordedEvents` is also a read-only `List<DomainEvent>`, which is what a test asserts on.

The usual owner of `consume()` is the repository, right after the aggregate is written, so an event is only ever
published for a change that was actually persisted:

```kotlin
override fun update(vararg entities: Order) {
    dao.update(entities.map { it.toDto() })
    eventDispatcher.publish(entities.flatMap { it.recordedEvents.consume() })
}
```

**Rebuilding an aggregate is not a business action.** When a repository rehydrates an aggregate whose constructor or
`init` records events (an `OrderPlaced`, say), it must call `recordedEvents.clear()` afterwards, or loading the
order would announce it as placed again.

---

## Domain errors

A `DomainError` is a failure the domain can explain: a rule that was broken, something that does not exist, an
operation that is not allowed right now. Everything in `errors/` extends it, and all but two are `open` so an
application can specialize them.

| Error | Use it when | Carries |
|---|---|---|
| `DomainError` | A business rule is broken and nothing more specific fits. Also what `fail` and `Ensure` throw. | |
| `NotFoundError` | The thing asked for does not exist. | |
| `AlreadyExistsError` | Creating something that already exists. | |
| `UniqueValueError` | A value that must be unique is already taken (an email, a code). | `name` of the field |
| `ExistingDependencyError` | Something cannot be removed (or changed) because other things still depend on it. | |
| `InvalidArgumentError` | An argument is not valid. | `name` of the argument |
| `RequiredArgumentError` | An argument is missing. An `InvalidArgumentError`. | `name` |
| `ArgumentCannotBeEmptyError` | An argument is present but empty. An `InvalidArgumentError`. | `name` |
| `InvalidOperationError` | The operation is not allowed in the current state (cancelling a delivered order). | |
| `ForbiddenError` | The actor is not allowed to do it. | |
| `ConcurrentModificationError` | Someone else saved the same thing between the moment it was read and the moment it was saved. | |
| `ValueTooLongError` | A value is longer than the storage allows (a text column). | |

`AlreadyExistsError` and `UniqueValueError` are final.

An application defines its own errors when a failure has a meaning its callers react to specifically:

```kotlin
class OrderAlreadyShippedError(val orderId: OrderId): DomainError("Order $orderId was already shipped")
```

Put what a caller needs in properties, not only in the message.

`trantor-web` turns these into HTTP responses: `NotFoundError` is a 404, `ForbiddenError` a 403,
`ConcurrentModificationError` a 409, and any other `DomainError` a 400 whose JSON body names the error type.
See [trantor-web](trantor-web.md#errors).

---

## `fail` and `Ensure`

Two helpers raise domain errors from domain code. They play the role that Kotlin's `error()` and `require()` play
elsewhere, but they throw a `DomainError` instead of an `IllegalStateException` or an `IllegalArgumentException`,
so the failure is reported as a broken business rule and not as a bug.

### `fail`

`fail(message)` throws a `DomainError` with that message. It returns `Nothing`, so it works in expressions:

```kotlin
if (!dueDate.isAfter(today)) fail("The due date must be after $today")

val line = lines.find { it.productId == productId } ?: fail("Product $productId is not in the order")
```

Use it for a rule that does not deserve its own error class.

### `Ensure`

`Ensure` is a set of checks, each of which throws when its condition does not hold. Every check comes in two forms:

- with a **field name**, which throws a `DomainError` whose message says what was wrong and, when it helps, what it
  got:

  ```kotlin
  Ensure.lengthBetween(code, 3..10, "code")   // "code length must be in 3..10, got 12"
  ```

- with an **error factory**, which throws the error you give it. This is the one to use when the caller must be able
  to tell this failure apart:

  ```kotlin
  Ensure.isFalse(order.isShipped()) { OrderAlreadyShippedError(order.id) }
  ```

Every check returns `Ensure`, so checks chain, and `ensure { }` runs a block with `Ensure` as its receiver:

```kotlin
ensure {
    notEmpty(lines, "lines")
    after(deliveryDate, orderDate, "deliveryDate")
    oneOf(paymentMethod, allowedMethods, "paymentMethod")
}
```

The checks, by group:

| Group | Checks |
|---|---|
| Booleans | `isTrue`, `isFalse` |
| Strings | `notBlank`, `notEmpty`, `lengthBetween`, `minLength`, `maxLength`, `matches`, `startsWith`, `endsWith`, `contains`, `notContains` |
| Numbers (`Int`, `Long`, `Double`, `BigDecimal`) | `positive`, `nonNegative`, `negative` (`Int`), `min`, `max`, `inRange`, `finite` (`Double`) |
| Any `Comparable` | `greaterThan`, `greaterThanOrEqual`, `lessThan`, `lessThanOrEqual`, `between` |
| Equality | `equal`, `notEqual` |
| Collections | `notEmpty`, `empty`, `minSize`, `maxSize`, `sizeBetween`, `contains`, `notContains`, `unique`, `allMatch`, `noneMatch` |
| Maps | `notEmpty`, `hasKey` |
| Dates (`LocalDate`, `LocalDateTime`) | `inPast`, `inFuture`, `before`, `after` |
| Membership | `oneOf` |

`inPast` and `inFuture` read the clock through `Clock`, so a test can stop it.

`fail` and `Ensure` are for the domain. Input that never reached the domain (a malformed request) is the job of
request validation (`ValidationMiddleware` in `trantor-core`).

---

## Repositories

`Repository<ID, T>` is the contract an aggregate's repository implements:

```kotlin
interface Orders: Repository<OrderId, Order> {
    fun findByNumber(number: String): Order?
}
```

It declares `get`, `getAll`, `add`, `update` and `remove`, each taking any number of aggregates or ids, plus
extensions that accept lists. The implementation lives outside the domain (for example on jOOQ, with `trantor-data`). By convention `get` throws `NotFoundError` and `find...` methods return null.

---

## Value objects

### `Email`

An email address that is **valid or does not exist**: building one with an invalid address throws
`InvalidEmailError`, so there is never a half-valid email in the model.

- Two emails are equal whatever their capitals, because mail servers say so.
- `toString()` keeps the capitals it was given, because that is what the user typed.
- It is never equal to the plain string that spells it.

### `Money`

An amount backed by `BigDecimal`, with no currency.

```kotlin
val total = Money("12.50") + Money(3) * 2       // $18.50
```

- Built from a `BigDecimal`, `String` (`"$ 12.50"` works), `Double`, `Float`, `Int` or `Long`, or from an unscaled
  value and a precision (`Money.unscaledLong(1250, 2)`), which is how an amount stored in cents comes back.
- Arithmetic operators, `Comparable`, `isZero()`, `isPositive()`, and `List<Money>.sum()`.
- Two amounts are equal, and hash the same, when they are numerically equal: `Money("2.0") == Money("2.00")`.
  `isZero()` also ignores the scale.
- `toString()` is `$12.50` (`-$12.50` when negative); `plainString()` is `12.50`.
- Division keeps **34 significant digits** (`MathContext.DECIMAL128`), so it never fails because a result does not
  end: `Money(1) / 3` is `0.3333…` with 34 digits. A finite result stays exact (`Money(150) / 80` is `1.875`).
  Dividing by zero still throws `ArithmeticException`.
- Rounding is explicit, at the edges: `rounded(precision)` rounds half even and keeps that scale
  (`Money(2).rounded(2)` reads `2.00`). `unscaledValue(precision)` / `unscaledLongValue(precision)` give the amount
  in minor units, rounding the same way; `unscaledLongValue` throws when it does not fit in a `Long`.

**To split an amount, allocate it; do not divide it.** `Money(100) / 3` rounded to cents is `33.33`, and three of
them add up to `99.99`. `allocate` splits the amount rounded to a precision into parts that add up to it exactly,
giving the minor units left over one each to the first parts:

```kotlin
Money(100).allocate(3, precision = 2)                  // [33.34, 33.33, 33.33]
Money(100).allocate(listOf(1, 1, 2), precision = 2)    // [25.00, 25.00, 50.00]
```

- A part with a zero ratio gets nothing, not even a leftover unit.
- A negative amount is split the same way, with the sign.
- No parts, an empty or negative ratio, or ratios that add up to zero fail with a `DomainError`.

`trantor-gson` serializes `Email` as its string and `Money` as its plain string.

---

## Tests

`AggregateTest`, `IdTest`, `RecordedEventsTest`, `EnsureTest`, `EmailTest` and `MoneyTest` cover the module. The
domain errors carry no logic and have no tests.
