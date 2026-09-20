# trantor-ai

Access to LLM providers behind one contract. This is the model layer: one call, one answer. Agents,
tools loops and runs are built on top of it, not here.

---

## Registering it

```kotlin
services.addAI()
```

That brings the `ModelRegistry` and the providers Trantor ships with. Middlewares are named by the
application, never collected from the container:

```kotlin
services.addAI { models, _ -> models.use(RetryMiddleware()) }
```

A provider's settings come from its own config section, and can be adjusted in code:

```kotlin
services.addOpenAI { openAI, services -> openAI.apiKey = services.config.required("openai.apikey") }
```

The order of `addAI` and `addOpenAI` does not matter. Both are idempotent.

```json
{
  "ai": {
    "providers": { "openai": { "baseUrl": "https://api.openai.com/v1" } },
    "models": { "default": "openai/gpt-4.1-mini", "smart": "openai/o4-mini" }
  }
}
```

The api key is **not** in the configuration file. It comes from `OPENAI_API_KEY`, and if it is missing the
failure happens on the first call, with a message saying what to set — not at startup, so an application
that never calls OpenAI never needs the key.

---

## Getting a model

```kotlin
val models = services.get<ModelRegistry>()

models.chat()                          // the "default" alias
models.chat("smart")                   // another alias from ai.models
models.chat("openai/gpt-4.1-nano")     // straight at a provider and model
```

An alias can point at another alias; a cycle is an error that names the chain. A reference to a provider
nobody registered is a `ModelNotFoundError` that lists the ones that are.

Resolved models are cached, and the cache is dropped whenever a provider, alias or middleware is added.

---

## Calling one

```kotlin
val response = models.chat().generate("Hola") { temperature = 0.2 }

println(response.text)
println(response.usage)
```

The shortcut above is an extension over the two methods every adapter implements. The full form is a
`ChatRequest`:

```kotlin
val response = models.chat().generate(
    ChatRequest(Message.system("Respondé en castellano"), Message.user("Hola")),
    CallOptions(timeout = 30.seconds),
)
```

```kotlin
models.chat().stream("Contame un cuento").use { stream ->
    for (part in stream) {
        when (part) {
            is StreamPart.TextDelta -> print(part.text)
            is StreamPart.ReasoningDelta -> { }
            else -> { }
        }
    }

    val response = stream.response()
}
```

`ChatRequest` carries what the call is about: `messages`, `tools`, `toolChoice`, `output`, `settings` and
`providerOptions`. `ChatSettings` holds the knobs — `temperature`, `maxOutputTokens`, `seed`, `reasoning`,
`failOnWarnings`. `CallOptions` is about the call and not the content: `timeout`, `cancellation`, `headers`.

A `ChatResponse` has `content` (a list of `Part`), `finishReason`, `usage`, `info` and `warnings`.

---

## The shapes

**Naming**: a name shared across model families carries no prefix — `CallOptions`, `Usage`,
`ResponseInfo`, `ModelWarning`. A name that belongs to chat carries it — `ChatModel`, `ChatRequest`,
`ChatResponse`, `ChatStream`.

**Parts**: `TextPart`, `ReasoningPart`, `ToolCallPart`, `ToolResultPart`, `RefusalPart`, and
`ProviderPart` for anything the adapter did not recognize. A `ProviderPart` carries the original payload
and goes back where it came from, so a field the provider added is visible instead of lost.

**Warnings, not silence.** When a request asks for something the provider cannot do, the response carries
a `ModelWarning`. `failOnWarnings` turns those into an error for an application that would rather stop.

---

## Structured output

```kotlin
@Serializable
data class Invoice(val number: String, val total: Double)

val response = models.chat().generate(
    ChatRequest(listOf(Message.user("Extraé los datos")), output = OutputSpec.json<Invoice>()),
)

val invoice = response.objectAs<Invoice>()
```

The schema is generated from the Kotlin class and closed before it is sent: every property required, no
open maps. Both providers ask for the same thing — OpenAI calls it strict mode, Anthropic only demands
`additionalProperties: false` — so the shaping is shared in `StrictSchema`.

Which wire field carries it is the adapter's business and not the caller's: `text.format` on OpenAI,
`output_config.format` on Anthropic. A model too old to have one drops it with a warning.

---

## Tools

```kotlin
val weather = FunctionToolSpec(
    name = "get_weather",
    description = "The weather in a city",
    parameters = JsonSchemas.of<WeatherQuery>(),
)

val response = models.chat().generate(
    ChatRequest(listOf(Message.user("¿Llueve en Bariloche?")), tools = listOf(weather)),
)

response.toolCalls.forEach { ... }
```

`ToolSpec` has two shapes: `FunctionToolSpec` for a tool the application runs, and `ProviderToolSpec` for
one the provider runs on its side.

The model layer **does not run tools**. It reports the calls the model asked for and sends back the
results it is given. Running them is the agent layer's job.

---

## Reasoning

```kotlin
models.chat("smart").generate("Resolvé esto") {
    reasoning = Reasoning.effort(ReasoningEfforts.High, ReasoningSummaries.Auto)
}
```

Providers take either an effort level or a token budget, so there is `Reasoning.effort(...)` and
`Reasoning.budget(...)`.

**A level is the portable way of asking.** It reaches every model that reasons: where the provider takes
levels it goes as a level, and where it takes a budget the adapter turns it into a share of what the
model can give. A budget is the exact way of asking and has nowhere to go on a model that only takes
levels, so there it is dropped with a warning.

Not every model has every level. `Minimal` exists on the first GPT-5 family and nowhere else; Anthropic
starts at `low`. A level the model does not have becomes the nearest one **below** it — thinking a little
less than asked is cheaper than thinking a lot more — and says so. The two levels above `High` that
Anthropic has are asked for with `AnthropicOptions.effort`.

Reasoning content the provider will not show comes back as an opaque `ReasoningPart`, which is passed
back on the next turn so the model keeps its own chain. It is signed or encrypted per provider, so an
adapter only takes back reasoning its own provider produced.

---

## Middlewares

`ChatModelMiddleware` wraps any model, with `transform`, `generate` and `stream`:

```kotlin
models.use(RetryMiddleware())
```

`RetryMiddleware` tries again only when the provider said the problem was temporary — a rate limit, a
5xx, a timeout, a broken connection. A bad key, a rejected request or a body that did not parse fails on
the first try. The call's `timeout` is a **deadline for the whole thing**, not for each attempt, and each
attempt gets what is left. A stream is reopened only while nothing has reached the caller.

Middlewares wrap in the order they were named.

---

## Providers

An adapter implements `AIProvider` and adds itself to the `ModelRegistry` through `configure<ModelRegistry>`,
so an application that never asks for it never pays for it.

Provider specifics that do not fit the contract travel in `ChatRequest.providerOptions` and come back in
`providerMetadata`. Each adapter reads only its own and warns about the rest. Options are merged into the
request body recursively: adding a field next to one the
adapter already set works, and a real conflict is an `InvalidProviderOptionError` naming the full path.

Currently **OpenAI** on the Responses API and **Anthropic** on the Messages API.

---

## The model catalog

### Why it exists

Providers take parameters out between generations of the same family, and the one left behind is not
ignored — it is a 400. The two that ship with Trantor both do it, on the settings people use every day:

| | what moved |
|---|---|
| OpenAI | A reasoning model refuses `temperature`: *"Unsupported value: 'temperature' does not support 0.2 with this model. Only the default (1) value is supported."* A model that does not reason refuses `reasoning`. |
| Anthropic | `temperature`, `top_p` and `top_k` stopped being accepted after Opus 4.6. A thinking budget in tokens gave way to an effort level, and the models from 4.7 on refuse a budget. Structured output does not exist before Sonnet 4.5. |

Without a catalog the same `ChatRequest` works or fails depending on which alias `ai.models.default`
happens to point at, so moving an alias from one model to another breaks an application that did not
change a line. It is a known failure mode, open as a bug in LiteLLM, LibreChat, graphiti, mealie and
inspect_ai.

### The rule

**The public api is wide and the adapter narrows it.** A setting the model would refuse is dropped with a
`ModelWarning` instead of being sent. That is principle 5 applied to the model and not only to the
provider: nothing is lost in silence, and nothing breaks that a caller wrote reasonably.

`ChatSettings(failOnWarnings = true)` turns any of these into an error, which is what an application in
production usually wants.

### A capability is a set or a range

A plain yes/no is the case where that set is empty or full:

```kotlin
data class ModelCapabilities(
    val maxOutputTokens: Int? = null,
    val temperature: ValueRange? = null,                        // null = does not accept it
    val topP: ValueRange? = null,
    val reasoningEfforts: Set<ReasoningEfforts> = emptySet(),   // empty = takes no levels
    val reasoningBudget: IntRange? = null,                      // null = takes no budget
    val features: Set<ModelFeatures> = emptySet(),
)
```

Inside a spec the answer is final: an empty `reasoningEfforts` means the model takes no levels, not that
nobody filled it in. With that, an adapter does **three** things rather than one, and each says so:

| | example |
|---|---|
| **drops** what does not fit | `temperature` on Claude Opus 5 |
| **coerces** what is out of range | `temperature = 1.8` goes to Anthropic as `1.0`, since it stops at 1 and OpenAI goes to 2 |
| **translates** to the nearest value the model has | `Minimal` goes to Anthropic as `low` |

### Data for what the model is, code for how settings meet

Some capabilities are not a property of the model but of two settings meeting. The GPT-5.x families
refuse the sampling settings **while they are reasoning**, and take them again once they are told not to
reason at all; GPT-6 cannot be told that, so for it the refusal is flat.

That condition stays as an `if` in the adapter. What the catalog holds is the plain fact it needs —
`ModelFeatures.ReasoningOff`, whether the model can be told not to reason — because the alternative is a
catalog filling up with flags like `rejectsTemperatureWhenReasoning`.

### Keeping it maintainable

**Written per family, not per model.** Within a generation every model takes the same things, so a
profile is a `val` and a family is a `copy`. All of Anthropic is nine entries:

```kotlin
add("anthropic/claude-opus-5", "anthropic/claude-sonnet-5", "anthropic/claude-opus-4-8",
    capabilities = effortOnly)

add("anthropic/claude-sonnet-4-6", "anthropic/claude-opus-4-6", capabilities = effortOnly.copy(
    temperature = ValueRange.ZeroToOne, reasoningBudget = MIN_BUDGET..128_000))
```

**A dated snapshot needs no entry.** `claude-sonnet-4-5-20250929` is answered by `claude-sonnet-4-5`, and
so are `@`, `:` and `-latest` suffixes. The three date shapes the providers use are `-20250929`,
`-2025-04-14` and the older `-0613`.

**Nothing else inherits**, deliberately. A loose prefix would make `gpt-4` answer for `gpt-4o` and
`gpt-4.1`, which are other models with other prices.

**A new model is described as the one before it**, which is the shape it almost always has:

```kotlin
services.addModelCatalog { catalog, _ ->
    catalog.add("anthropic/claude-6", like = "anthropic/claude-opus-5") { copy(maxOutputTokens = 256_000) }
}
```

`like` means *"the same capabilities as that model, then apply this change"*. It is resolved **when the
catalog is read, not when the line runs**, so it can name a model whose provider has not registered yet
and the order of the calls does not matter — the same rule the registry follows. A `like` that names no
model is an error; the default below never answers in its place, so a typo stays a typo.

**In code and not in the configuration.** Aliases live in `ai.models` because an alias is a string
pointing at another string and there is nothing to check. A capability is a level, a range or a feature,
and written as text none of them are checked until the call goes out. In Kotlin there is autocomplete
while it is written and the compiler answers all three, and an application recompiling its own code is
not a release of Trantor.

**What the world already maintains is not maintained here.** LiteLLM and models.dev publish prices,
windows and capability flags for thousands of models — but neither has a field for what a model
*refuses*, which is why LiteLLM has the GPT-5 temperature bug open. So `ModelSpec.pricing` can be fed
from there one day, and `ModelSpec.capabilities` is ours, small and checked against real calls.

The two halves are not the same kind of fact:

| | pricing | capabilities |
|---|---|---|
| in the call path? | no | **yes** |
| if it is wrong | a report comes out wrong | a call breaks, or a setting is dropped without a word |
| how it is checked | against the price list | **against a real call**, the way a fixture is |

### A model nobody described

It **stands in for the newest model of its provider**, which is what `addDefault` names:

```kotlin
addDefault("anthropic", like = "anthropic/claude-opus-5")
```

A model that comes out is almost always the one before it with something taken away, so the newest entry
is the closest guess there is, and the call goes out working instead of failing on a parameter that
generation stopped taking.

A spec that came from there is marked `ModelSpec.isGuess`, and **every decision an adapter takes out of
one says so in its warning**:

> `claude-sonnet-9 does not take temperature, so it was not sent. That is what the newest model in the
> catalog takes; add claude-sonnet-9 to it if it takes more.`

That is the price, stated: a guess can drop something the model did accept, which a written entry cannot.
Saying it out loud is what keeps it from being silent. The same applies to an id that is not the
provider's model at all — an Anthropic-compatible server behind `anthropic/…` gets Claude's profile — and
one `catalog.add` fixes it.

### The one value invented out of nothing

Anthropic **requires** `max_tokens`, so there has to be a number even for a model with no ceiling known.
That is the only place a value is made up, and it comes with a warning naming the model and what to do:

> `claude-x is not in the model catalog, so the call asks for 4096 output tokens. Add it to the catalog,
> or set maxOutputTokens.`

Everywhere else the rule holds: **a value is only invented when the api forces it, and never in silence.**

### Reading it

```kotlin
val spec = services.get<ModelCatalog>().find("anthropic", "claude-opus-5")

if (ModelFeatures.StructuredOutput in spec!!.capabilities) { ... }
```

Each provider registers its own models, so `addAI()` is all an application needs.

---

## Anthropic

```kotlin
services.addAnthropic { anthropic, _ -> anthropic.cache = AnthropicCaches.Automatic }
```

The key comes from `ANTHROPIC_API_KEY`, and `AnthropicConfig` also holds `baseUrl`, the pinned
`anthropic-version`, a list of `betas` sent as `anthropic-beta`, `defaultMaxTokens` and
`midConversationSystemMessages`.

The Messages API is shaped differently from the Responses API, and most of the adapter is that:

| | OpenAI | Anthropic |
|---|---|---|
| shape | a flat list of **items** | messages, each holding **blocks** |
| system | an item with a role | a field of the request |
| roles | any order | must alternate, so two in a row are merged into one |
| `max_tokens` | optional | **required**, so there is always a number to send |
| tool result | an item of its own, carrying the tool name | a block inside a `user` message, with no name |
| cached tokens | already inside `input_tokens` | counted **apart** from it |

**Usage is normalized.** Anthropic charges input, cache reads and cache writes at three different prices
and reports them apart, so the adapter adds the three into `Usage.inputTokens` and leaves the two cache
ones named beside it. `Usage` says the details are **subsets**, and without this the same field would
mean one thing here and another in OpenAI, and every sum would be wrong by however much the cache was
used.

**The system prompt** is a field, so the first one goes there wherever it was written. The rest are
joined into it by default, which every model takes; `midConversationSystemMessages = true` sends the
later ones in place as `role: "system"` messages, which keeps the cached prefix intact but only the
newest models accept.

**Prompt caching is one flag.** Unlike OpenAI, which caches on its own, Anthropic only caches what was
marked — but it has a top-level mark that puts the cut on the last cacheable block and moves it forward
as the conversation grows:

```kotlin
anthropic.cache = AnthropicCaches.Automatic          // or AutomaticForAnHour
```

Below the model's minimum, around a thousand tokens, nothing is cached and nothing fails either, so
leaving it on is never wrong. For a cut somewhere precise, put `cache_control` in the `ProviderMetadata`
of the part it goes after; there are four marks per request and the automatic one takes one of them.

**`AnthropicOptions`** is the way past the catalog. It is sent as it was written, without asking what the
model takes, so whoever knows their model gets exactly what they asked for: `effort` reaches the `xhigh`
and `max` levels `Reasoning` does not have, `thinking` names a budget or the adaptive mode, and there are
`cache`, `userId` and `serviceTier`.

---

## Cancellation

```kotlin
val cancellation = Cancellation()

models.chat().generate(request, CallOptions(cancellation = cancellation))
```

Cancelling closes the connection. Because the transport reports that as an end of data rather than an
error, what was read is checked afterwards: half an answer is a `CancelledError`, never an answer. The
listener is registered before the call is opened, since that wait is itself cancellable, and removed when
the call ends, so a token reused across calls does not accumulate callbacks.
