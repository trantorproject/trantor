# trantor-gson

The `JsonSerializer` of Trantor, on **Gson**. It is what turns the body of a request into the request of a use
case, and its response into JSON; jobs, events and the cache go through it too. Gson is made for Java, so this
module adds what it takes to read Kotlin classes as Kotlin builds them, and the types of the domain.

---

## Registering it

An `Application` registers it on its own, as the `JsonSerializer` of the container. To add the adapters of the
application:

```kotlin
services.addGsonSerializer { gson, _ ->
    gson.registerTypeAdapter(ToolKey::class.java, StringValueSerializer({ ToolKey(it) }, { it.value }))
    gson.registerTypeAdapterFactory(
        HierarchyTypeAdapterFactory.of<CustomToolConfig>()
            .subtype<HttpToolConfig>("http")
            .subtype<ScriptToolConfig>("script"),
    )
}
```

`addGsonSerializer` is idempotent, and its configuration runs whichever order the calls go. A `JsonSerializer` the
application registers before is kept, and the configuration only applies when it is a `GsonSerializer`.

The `Gson` is built once and kept. Building it throws away the adapters it worked out for each type, so it is
built again only after an adapter is registered. `getGson()` gives the same one, for a library that wants a `Gson`
of its own, like the scheduler.

---

## Kotlin classes

A Kotlin class is read through its **primary constructor**, and not by setting its fields the way Gson reads Java:

```kotlin
data class CreateChatbot(
    val name: String,
    val model: String = "default",
    val description: String? = null,
) {
    init {
        require(name.isNotBlank()) { "The name cannot be blank" }
    }
}
```

- **A field that is left out takes its default**, even one that depends on another parameter.
- **A field that cannot be null and has a default takes it when it comes as `null`**, as if it were left out. The
  strict mode of OpenAI sends every field of a schema, and `null` for the ones the model has nothing for.
- **A field that cannot be null and has no default is required:** leaving it out, or sending `null`, fails with
  `name cannot be null in type 'CreateChatbot'`. A nullable one left out is `null`.
- **What a list or a map holds is checked against its Kotlin type:** a `null` in a `List<String>` fails saying where,
  as `tags[1] cannot be null in type 'Basket'`, and one in a `List<String?>` stays.
- **The `init` block runs**, and what it throws comes out as it is.
- **The constructor can be private.**
- **A field goes by its name, or by its `@SerializedName` and its alternates.** Names are matched ignoring case.
- **A field the class does not have is skipped.**
- **A property of the body with a field of its own** (`var notes: String? = null` in the body) is read after the
  constructor runs.
- **Generic classes** take the type they are declared with: a `Page<Item>` field reads `Item`s.

**A value class** (`@JvmInline value class Code(val value: String)`) is its value on the wire, alone or as a field:
`{"code":"AR"}`.

**Numbers are lenient:** `3.0` and `"3"` are the `Int` 3; `3.5` for an `Int` fails. A number sent for a `String` is
its text.

**Booleans and enums are strict:**
- A boolean is `true` or `false`, or those words as strings in any case. Anything else fails, saying what came. Gson
  alone reads any other string as `false`.
- An enum goes by the name of each value, or its `@SerializedName`. A value it does not have fails naming the ones it
  has (`'red' is not one of RED, BLUE at $.color`). Gson alone reads it as `null`, which a class then reports as a
  field that cannot be null.

A failure to read is a `JsonParseException`, which the web answers with 400.

### Writing

A class is written by its fields, as Gson writes any object:

- a `null` field is left out;
- the properties of the body with a field are written, and the computed ones (`val x get() = …`) are not;
- a delegated property, like `val upper by lazy { … }`, is not written, since its field holds the delegate and not
  the value;
- a field marked `@Transient` is not written.

---

## The types of the domain

| Type | On the wire |
|---|---|
| Any `Id` | Its uuid, as a string |
| `Money` | Its plain amount, as a string (`"10.50"`); read from a string or a number |
| `Email` | The address |
| `LocalDate`, `LocalDateTime`, `LocalTime` | ISO 8601 |
| `YearMonth` | `"2026-09"` |
| `Maybe<T>` | See below |

A value that cannot be read, like an id that is not a uuid or a date that is not a date, is a `JsonParseException`
with the value and the type. A domain error, like `InvalidEmailError` for an address that does not exist, goes on
as it is.

### Maybe, for partial updates

`Maybe` tells a field that was left out from one sent as `null`, which a partial update needs:

```kotlin
data class UpdateCustomer(
    val id: CustomerId,
    val phone: Maybe<String?> = Maybe.None,
)
```

| JSON | `phone` |
|---|---|
| `{"id": "…"}` | `Maybe.None`: leave it as it is |
| `{"id": "…", "phone": null}` | `Maybe.Value(null)`: remove it |
| `{"id": "…", "phone": "123"}` | `Maybe.Value("123")` |

It is written the same way: `None` is left out, and `Value(null)` is written as `null`.

---

## Value objects of the application

A class with a single value, like a key or a code, is registered with `StringValueSerializer`, which reads it from a
string and writes it as one:

```kotlin
gson.registerTypeAdapter(ToolKey::class.java, StringValueSerializer({ ToolKey(it) }, { it.value }))
```

Without the second function it is written with `toString()`, which is only right when `toString()` gives the value.
What the first function throws as `IllegalArgumentException` is a `JsonParseException`; anything else, like a
domain error, goes on as it is.

---

## Hierarchies

A base class with several subtypes is read by a label in the object, `type` unless another name is given:

```kotlin
gson.registerTypeAdapterFactory(
    HierarchyTypeAdapterFactory.of<CustomToolConfig>()
        .subtype<HttpToolConfig>("http")
        .subtype<ScriptToolConfig>("script"),
)
```

```json
{ "type": "http", "url": "https://…" }
```

The subtype is written with its label, and read by it, also inside lists. A label it does not know fails naming
the ones it has, and an object without one fails saying it has none. Labels are matched as they are, case included.
`HierarchyTypeAdapterFactory.of<T>(typeFieldName, maintainType)` changes the name of the label, and whether the
subtype keeps it as a field of its own.

---

## The schema of what it reads

`GsonSerializer` is a `JsonSchemaSource`: it tells, as a JSON Schema, what it reads for a type, by the same rules
it reads by. That is what a model is given for the arguments of a tool, or what an API can document for a request.

```kotlin
val schema = serializer.schemaOf<PlaceOrder>()
```

- A class is an object with a property for each parameter of its primary constructor, by its serialized name. A
  parameter that cannot be null and has no default is `required`; a nullable one also takes `null`. The properties
  of the body are left out: they are not how the class is built.
- The classes, enums and hierarchies it uses go to `$defs`, by their simple name (with the package when two share
  it), and a class that contains itself refers to itself.
- A hierarchy is `anyOf` its subtypes, each with its label as a `const` it requires. A nullable object is `anyOf` it
  and `null`. Neither OpenAI nor Anthropic take `oneOf`.
- Ids and uuids are strings with `format: uuid`, `Email` with `format: email`, `LocalDate` with `format: date`,
  `LocalDateTime` with `format: date-time` (its parser needs an offset, as that format does), and `LocalTime` and
  `YearMonth` with a `pattern`. `Money` is a string. A `Maybe` is what it holds, never required, and a value class
  is its value.
- The validations of Jakarta say up front what the validation will ask for: `@NotBlank` is `minLength: 1`, `@Size`,
  `@Min`, `@Max`, `@DecimalMin`, `@Positive` and the like are their keywords, `@Pattern` is `pattern` and `@Email`
  is `format: email`. They are read from the field (`@field:NotBlank`) or from the parameter.
- `@Description`, from `trantor-primitives`, on a class or a field, is its `description`: what the name and the type
  do not say.

```kotlin
@Description("An order of a customer")
data class PlaceOrder(
    @Description("The customer who buys") val customer: CustomerId,
    @field:NotEmpty val lines: List<OrderLine>,
)
```

**An adapter of the application says what it reads.** A `StringValueSerializer` is a string on its own, or the
schema it is given:

```kotlin
gson.registerTypeAdapter(Sku::class.java, StringValueSerializer({ Sku(it) }, { it.value }, skuSchema))
gson.registerTypeAdapter(Point::class.java, PointAdapter(), Json.obj("type" to "string", "pattern" to pointPattern))
gson.registerSchema(Point::class.java, pointSchema)   // for an adapter registered without one
```

A type read by an adapter that gave no schema, or by a factory other than a `HierarchyTypeAdapterFactory`, fails
with a `JsonSchemaError` that says where it is (`PlaceOrder.lines[].sku`) and how to give it one. The schema is
never guessed.

---

## Tests

`GsonSerializerKotlinTest` is the reference for how a Kotlin class is read and written: each rule above is a test.
`GsonSerializerSchemaTest` does the same for the schema. `GsonSerializerValuesTest` covers the types of the domain,
and the adapters have tests of their own.
