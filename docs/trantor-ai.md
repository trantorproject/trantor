# trantor-ai

Access to LLM providers behind one contract, and a facade for use cases that runs the tools a model asks
for. The model layer is one call, one answer; the facade and its tool loop are built on top of it, and
agents will be built on the same loop.

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

## The facade and the tool loop

A use case talks to models through `AI`, which `addAI()` registers. It keeps no state, so it is injected
like any other service and mocked in the tests of the use case.

```kotlin
class AnswerCustomer(private val ai: AI, private val products: SearchProductsTool) {
    fun execute(question: String): String {
        val summary = ai.text("Resumí en una línea: $question", model = "fast")

        val result = ai.generate {
            system("Sos el asistente de una ferretería")
            user(question)
            tools(products)
        }

        return result.text
    }
}
```

`ai.text` is one call and its text. `ai.generate { }` is a **run**: the model is called, the tools it asks
for are run, and it is called again with their results, until it answers without asking for more. Each
call to the model and the tools it asked for is a **step**. The words are the ones AI SDK uses; "turn" is
left out because it also means an exchange between the user and the application.

The builder takes the messages in the order they are written — `system`, `user`, `messages(history)` —
and `dynamicSystem` for the part of the instructions that changes from one call to the next (see [What
changes goes last](#what-changes-goes-last)). It also takes `model` (a reference or an alias, `default` when
left out), `tools`, `toolChoice`, `maxSteps`,
`settings { }`, `options(...)` for the provider, `context(RunContext)` for the tools and
`callOptions(...)` for the timeout and the cancellation, which reach every step.

### What a run gives back

```kotlin
result.text              // what the model answered last
result.steps             // each call to the model, with the results of its tools
result.usage             // added up over the steps
result.estimatedCost     // added up too; null if any step has no estimate
result.newMessages       // what the run added to the conversation, to keep it
result.toolFailures      // the exceptions of the tools that failed, for the application
```

`newMessages` is what to store to continue the conversation later: the answers of the model, whole, and
the results of the tools. A step whose calls were not run is left out of it, because a tool call without
its result is something every provider refuses.

### Objects

```kotlin
val invoice = ai.generate<Invoice> { user("Extraé los datos de la factura: $text") }

val result = ai.generateObject<Invoice> { user("Extraé los datos de la factura: $text") }
result.value             // null when the model did not give it
result.refusal           // what it said instead, if it refused
```

`generate<T>` fails with `NoObjectGeneratedError` when the model refused, ran out of tokens or wrote
something that is not a `T`. `generateObject<T>` says so without failing, for whoever wants to decide what
to do. The schema of `T` goes on every step, tools included: the model can call tools and answers with the
object at the end.

### Tools

```kotlin
class SearchProductsTool(private val catalog: Catalog): Tool<SearchProductsTool.Args>(Args.serializer()) {
    override val name = "searchProducts"
    override val description = "Products of the store whose name contains the text"
    override val readOnly = true

    override fun execute(args: Args, context: ToolContext) = ToolResult.json(catalog.search(args.text))

    @Serializable
    data class Args(@SerialDescription("Part of the name of the product") val text: String)
}
```

A tool plays the part of a controller: it turns what the model asked for into an operation of the
application, and its result into something the model can read. The schema the model sees comes from the
same serializer that decodes what it sends back, so the two cannot drift apart. The args are decoded
leniently: a field the args do not have is ignored, and a nullable arg the model left out reads as null.
An optional arg is best nullable, since OpenAI in strict mode sends every field and Anthropic leaves out
the ones it has nothing for.

`ToolContext` carries the `callId`, the `toolName` and the `RunContext` of the run, a typed bag that
whoever launches the run fills: `context.run.require<Tenant>()`. What a tool needs from the application —
a repository, the executor — it gets by constructor, like any other service.

Tools the provider runs on its side, like a web search, are not run again: their results came in the
answer.

### When a tool fails

A failing tool **does not fail the run**. The model gets a result marked as an error and can try
something else:

| What happened | What the model reads |
|---|---|
| The input does not fit the args | The detail, so it can fix the call |
| It asked for a tool that does not exist | The names of the ones that do |
| The tool threw `ToolError` | Its message, which the tool wrote for the model |
| Any other exception | `Tool execution failed`, unless a `ToolErrorHandler` has something safe to say |

The message of any other exception was written for a developer and could reach the user, so it does not
go to the model. It stays in `result.toolFailures` and in the log. An application that has something safe
to say about its own exceptions registers a handler:

```kotlin
services.addToolErrorHandlers { handlers, _ ->
    handlers.add { error, _ -> if (error is ProductNotFoundError) "There is no such product" else null }
}
```

Handlers are asked in order and the first that answers wins. A tool with `onError = ToolErrorModes.FailRun`
fails the run with its exception instead, and a cancellation always ends it.

### Steps and their limit

A run takes at most `maxSteps` calls to the model, 10 by default. Past them it throws
`MaxStepsExceededError`, with what the run did so far in `error.result`. The calls of that last step are
**not** run: no model would read their results, and a tool with effects would have them all the same.

The limit fails instead of handing back what there is, which is what AI SDK does: a use case that expects
a text would get an empty one without noticing.

### Calls at the same time

When the model asks for several calls in one step and **every** tool of the step is `readOnly`, they run
at the same time, each on its own virtual thread. Otherwise they run one after the other, in the order the
model asked for them, since two calls that write could depend on each other.

- `readOnly` is `false` by default: a tool says it only reads, and nobody checks it.
- The results go back in the order of the calls, not in the order they finished.
- They all finish before the run goes on, even when one fails, and each failure is handled as above.
- A cancellation interrupts every one of them.
- A tool that is `readOnly` can run on two threads at once, so it cannot keep mutable state of its own.

### Streaming a run

```kotlin
ai.stream { user(question); tools(products) }.use { stream ->
    stream.textDeltas().forEach { print(it) }

    val result = stream.result()
}
```

A `RunStream` is the same run received as it happens, as `RunEvent`s: `StepStarted`, `Model` with what
the model produces (a `StreamPart`, as the adapter read it), `ToolStarted`, `ToolFinished` and
`StepFinished`. A step whose calls run at the same time says they all started before any of them finished.

`result()` consumes whatever is left and gives the `RunResult`, and closing the stream closes the call in
flight and runs no more tools.

### Without the facade

`ToolLoop` is the loop the facade runs on, for whoever has a `ChatModel` in hand:

```kotlin
val result = ToolLoop(model, listOf(weather), maxSteps = 5).run(ChatRequest("¿Llueve en Bariloche?"))
```

That loop sends every step with the same model and tools. The loop can also ask, before each step, what to send it
with: a `NextStep` gets the request with the whole conversation so far and the steps done, and answers with a
`StepSetup` — the model, the request and the tools. It is what agents run on: after a handoff the next step goes out
with another agent, and the instructions and the history sent are worked out again every time.

```kotlin
val loop = ToolLoop({ request, steps ->
    val agent = if (steps.isEmpty()) support else sales
    val messages = listOf(Message.system(agent.instructions)) + request.messages
    StepSetup(agent.model, request.copy(messages = messages), agent.tools)
})
```

The loop keeps the rest: the conversation stays whole whatever a step sends of it, the calls of an answer run with the
tools of the step that got it, and the tools are asked for their description again on every step.

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
    for (part in stream) if (part is StreamPart.TextDelta) print(part.text)
}
```

`ChatRequest` carries what the call is about: `messages`, `dynamicSystem`, `tools`, `toolChoice`, `output`,
`settings` and `providerOptions`. `ChatSettings` holds the knobs — `temperature`, `maxOutputTokens`, `seed`,
`reasoning`, `failOnWarnings`. `CallOptions` is about the call and not the content: `timeout`,
`cancellation`, `headers`.

A `ChatResponse` has `content` (a list of `Part`), `finishReason`, `usage`, `info` and `warnings`.

### What changes goes last

```kotlin
ChatRequest(
    messages = listOf(Message.system("Sos el asistente de una ferretería..."), Message.user(question)),
    dynamicSystem = "Hoy es martes 23/09. El contacto está asignado a Juan.",
)
```

A system prompt usually has a long part that never changes and a short one that does: the time, what the
application knows about the user, the state of a process. Providers cache the beginning of a request that
repeats, and they read the tools first, then the system prompt, then the conversation. So a part that
changes at the end of the system prompt still comes before the whole conversation, and every time it
changes the conversation is paid for again.

`dynamicSystem` is that part, kept apart. Each adapter puts it last:

| | where it goes |
|---|---|
| OpenAI | a system message at the end of the input |
| Anthropic, a model that takes a system message in the middle | a `role: "system"` message after the conversation |
| Anthropic, a model that does not | a second block under the system prompt, after the mark of the system cache |
| Anthropic, a model that ties its thinking to the conversation (Opus 5.5, Fable 5.1) | a system message after the conversation that lasts one turn, with the earlier ones put back where they were (see [Thinking tied to the conversation](#thinking-tied-to-the-conversation)) |

It is not a message of the conversation: it never shows up in `newMessages`, and a run sends it again on
every step. The facade has it as `dynamicSystem(...)`.

This is what both providers recommend. OpenAI's prompt caching guide puts the *"dynamic developer
instructions, such as user-specific content and timestamps"* after the stable ones, and Anthropic added
system messages in the middle of the conversation because editing the top one *"invalidates the cache for
everything that follows"*. PydanticAI keeps its dynamic instructions after the static ones, and ADK sends
the dynamic part after the conversation as content of the user; here it keeps the role of a system message
wherever the model takes one.

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

**Usage is totals and their parts.** `inputTokens` and `outputTokens` are totals. The input splits into
`uncachedInputTokens`, `cacheReadTokens` and `cacheWriteTokens`, which add up to it, and `reasoningTokens`
is part of the output. Each part is named for what happened to those tokens because each is priced apart;
"cached" alone would not say whether it was read or written. Null means the provider did not say.

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
one the provider runs on its side. A tool the provider ran comes back as a `ToolCallPart` with
`providerExecuted = true`, so the loop knows there is nothing to run and nothing to answer for it.

The model layer **does not run tools**. It reports the calls the model asked for and sends back the
results it is given. Running them is the job of the tool loop, in [The facade and the tool
loop](#the-facade-and-the-tool-loop).

**Who chooses** is `toolChoice`: `Auto` — the model decides, and the default — `Required` for any of
them, `Named` for one by name, and `None`. `ChatSettings.parallelToolCalls = false` asks for one call at
a time; which field carries that is the adapter's business, and on Anthropic it is not a field of the
request but part of the choice.

**A tool is strict by default**, which holds the model to the schema instead of hoping, and the schema is
closed exactly the way structured output closes it. Where the model has no grammar for it the tool still
goes, without the guarantee and with a warning: half a tool beats no tool.

**Being told to call one is not something every model takes.** Claude Opus 5.5, Fable 5.1 and Mythos 5.1
answer 400 to a forced call, and any Claude refuses it while it is thinking to a budget — the second capability that
is not a property of the model but of two settings meeting. Either way the choice goes back to `auto`,
which is the default anyway, and the warning says which of the two it was.

---

## Streaming

```kotlin
models.chat().stream("Contame un cuento").use { stream ->
    for (part in stream) { /* ... */ }

    val response = stream.response()
}
```

`ChatStream` is a **blocking pull iterator**: reading blocks the virtual thread, backpressure comes for
free, and closing cancels the call. That is why it is a `use {}` and not a callback.

Four things come out of it. `TextDelta` and `ReasoningDelta` are text as it is being written; `PartDone`
is a block that finished, already assembled — a whole text, a reasoning block, a tool call with its input
parsed; and `Raw` is an event the adapter does not map, handed over instead of dropped, so the day a
provider adds one it is visible rather than lost.

**`stream.response()` is the same `ChatResponse` a `generate` would have returned**, usage and finish
reason included. It consumes whatever is left of the stream, so it can be the only thing that is called.

The two apis are not symmetric about that and the adapters hide it. OpenAI sends the whole response as a
last event. Anthropic sends nothing of the kind: a message opens empty, its blocks arrive one by one and
the last events say how it ended — so its adapter puts the message back together as it arrives and runs
it through the same mapper a plain call uses. A stream cut before the end still answers, with what
arrived and a warning saying so.

A tool call is the one thing that cannot be handed over early: its input travels as pieces of text that
are not json until the last one arrives, so it comes whole in a `PartDone` or not at all.

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

`CostMiddleware` puts what the call probably cost in `response.info.estimatedCost` — see [Cost](#cost).
It takes the catalog the providers share, so the container builds it:

```kotlin
services.addAI { models, services ->
    models.use(RetryMiddleware())
    models.use(services.create<CostMiddleware>())
}
```

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
reason at all; GPT-6 Astra cannot be told that, so for it the refusal is flat. GPT-6 Sol and Luna can be
told not to reason, but nothing says they take a `temperature` then, so they are kept without one.

Being told not to reason is itself something a model can refuse: GPT-6 Astra answers 400 to an effort of
`none`, and Claude Opus 5.5, Fable and Mythos always think and answer 400 to thinking disabled. There
`Reasoning.Off` is not sent, and the warning says that a lower effort is how they think less.

Anthropic has one of its own: every Claude refuses a forced tool call while it is thinking to a budget,
whichever model it is.

Those conditions stay as an `if` in the adapter. What the catalog holds is the plain fact each one needs
— `ModelFeatures.ReasoningOff`, whether the model can be told not to reason; `ModelFeatures.ForcedToolUse`,
whether it can be told to call a tool at all — because the alternative is a catalog filling up with flags
like `rejectsTemperatureWhenReasoning`.

### Keeping it maintainable

**One line per model, with everything known about it.** Within a generation every model takes the same
things, so what it takes is a profile — a `val`, changed with `copy` — and the line names it. What it
costs goes on the same line, so asking what the catalog knows of a model has one place to look:

```kotlin
add("anthropic/claude-opus-5", effortOnly, opusPrice)
add("anthropic/claude-sonnet-4-6", bothWays, sonnetPrice)
add("openai/gpt-5.4", reasoningOptional, ModelPricing(input = "2.50", output = "15", cacheRead = "0.25"))
```

Models come out a few times a year, and a line each is what they cost to write down.

**A dated snapshot needs no entry.** `claude-sonnet-4-5-20250929` is answered by `claude-sonnet-4-5`, and
so are `@`, `:` and `-latest` suffixes. The three date shapes the providers use are `-20250929`,
`-2025-04-14` and the older `-0613`.

**Nothing else inherits**, deliberately. A loose prefix would make `gpt-4` answer for `gpt-4o` and
`gpt-4.1`, which are other models with other prices.

**A new model is described as the one before it**, which is the shape it almost always has:

```kotlin
services.addModelCatalog { catalog, _ ->
    catalog.add("anthropic/claude-6", like = "anthropic/claude-opus-5", ModelPricing(input = "5", output = "25")) {
        copy(maxOutputTokens = 256_000)
    }
}
```

`like` means *"the same capabilities as that model, then apply this change"*. It is resolved **when the
catalog is read, not when the line runs**, so it can name a model whose provider has not registered yet
and the order of the calls does not matter — the same rule the registry follows. A `like` that names no
model is an error; the latest model below never answers in its place, so a typo stays a typo.

**In code and not in the configuration.** Aliases live in `ai.models` because an alias is a string
pointing at another string and there is nothing to check. A capability is a level, a range or a feature,
and written as text none of them are checked until the call goes out. In Kotlin there is autocomplete
while it is written and the compiler answers all three, and an application recompiling its own code is
not a release of Trantor.

**What a model refuses is maintained here, because nobody else does.** LiteLLM and models.dev publish
prices, windows and capability flags for thousands of models — but neither has a field for what a model
*refuses*, which is why LiteLLM has the GPT-5 temperature bug open. So `ModelSpec.capabilities` is ours,
small and checked against real calls. Prices are ours too, for now, read off each provider's price
list, which is short enough to keep by hand.

The two halves are not the same kind of fact:

| | pricing | capabilities |
|---|---|---|
| in the call path? | no | **yes** |
| if it is wrong | a report comes out wrong | a call breaks, or a setting is dropped without a word |
| how it is checked | against the price list | **against a real call**, the way a fixture is |

### A model nobody described

It **is taken to be the latest model of its provider**, which is what `setLatest` names:

```kotlin
setLatest("anthropic", "anthropic/claude-opus-5-5")
```

One slot per provider, so setting it again replaces it. When the next generation comes out, that line is what
moves.

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

## Cost

**It is an estimate and not the bill.** `CostCalculator` prices the tokens of a call with the list price of
its model, and that is all it knows. Batch and priority tiers, the higher price of a long context, where
the data is kept, discounts and taxes all move the bill away from it. It is close enough to see where the
money goes and to notice when it starts going faster, which is what it is for.

With `CostMiddleware` registered, every response carries it:

```kotlin
val estimate = response.info.estimatedCost

estimate?.cacheRead      // what the cache cost, next to what it saved on estimate.uncachedInput
estimate?.reasoning      // part of estimate.output
estimate?.total
```

A stream's parts go through untouched and its `response()` carries the estimate, since the tokens are
only known once the answer is over. Without the middleware, `CostCalculator(catalog).estimate(response)`
gives the same number.

A `CostEstimate` has the names of `Usage` without the `Tokens`: `input` is `uncachedInput` plus
`cacheRead` plus `cacheWrite`, `reasoning` is part of `output`, and `total` is input plus output. Estimates
of several calls add up with `+`, part by part. Nothing is rounded; a call costs fractions of a cent, and
rounding belongs to whoever shows the number.

A price is per million tokens, in dollars, on the line of the model it belongs to. Each provider brings
the prices of its models. An application prices a model it adds on the same line, and corrects a price,
or prices a model it never describes, with `price`:

```kotlin
services.addModelCatalog { catalog, _ ->
    catalog.price("openai/gpt-5.2", ModelPricing(input = "1.75", output = "14", cacheRead = "0.175"))
}
```

The numbers are text so they stay exactly what the price list says. A price is all an estimate needs,
so a model priced and never described in the catalog is estimated too. `inputPerMillion` is the plain price,
the one both providers call "input", and it is what the uncached input pays. A cache with no price of its
own is charged as plain input, which is what OpenAI does with a write. There is one price for writes,
although Anthropic charges more for a cache kept an hour: for an estimate, the five-minute price is close
enough.

**No number is better than a wrong one.** The estimate is null when:

- the catalog does not know the model, or knows it without a price;
- the model is standing in for the newest one. Guessing what a model takes keeps a call from failing;
  guessing what it costs gives a wrong number that looks right, off by however much cheaper the newest
  model is. Being off by a tier is an estimate, being off by a model is not;
- the usage does not say the input or the output, because half a bill passes for all of it.

A response is priced as `info.model`, the model that answered. That is usually a dated snapshot, and a
snapshot is priced as its family unless it has a price of its own, which a couple of old OpenAI snapshots
do. A model added `like` another takes what the other takes and not what it costs, so it has no price
until one is written for it. A model standing in for the newest one gets nothing of the newest one's
price, but keeps its own if somebody wrote it: what it takes is still a guess, what it costs is not.

The prices shipped with Trantor are the standard ones, read off the pricing pages of each provider. For
OpenAI that is the short-context price; GPT-6 Astra, GPT-5.6, GPT-5.5 and GPT-5.4 charge more past a long
prompt. Claude 3 Haiku has no price, because Anthropic no longer lists one.

---

## Anthropic

```kotlin
services.addAnthropic { anthropic, _ -> anthropic.cache = AnthropicCache(system = true, conversation = true) }
```

The key comes from `ANTHROPIC_API_KEY`, and `AnthropicConfig` also holds `baseUrl`, the pinned
`anthropic-version`, a list of `betas` sent as `anthropic-beta` and `defaultMaxTokens`.

The Messages API is shaped differently from the Responses API, and most of the adapter is that:

| | OpenAI | Anthropic |
|---|---|---|
| shape | a flat list of **items** | messages, each holding **blocks** |
| system | an item with a role, anywhere | a field of the request, and a message anywhere on the newest models |
| roles | any order | must alternate, so two in a row are merged into one |
| `max_tokens` | optional | **required**, so there is always a number to send |
| tool result | an item of its own, carrying the tool name | a block inside a `user` message, with no name |
| parallel tool calls | a field of the request | a field of `tool_choice` |
| cached tokens | already inside `input_tokens` | counted **apart** from it |
| end of a stream | a last event with the whole response | nothing: it is assembled from the events |

**Usage is normalized.** Anthropic charges input, cache reads and cache writes at three different prices
and reports them apart, so the adapter adds the three into `Usage.inputTokens` and leaves the two cache
ones named beside it. `Usage` says the details are **subsets**, and without this the same field would
mean one thing here and another in OpenAI, and every sum would be wrong by however much the cache was
used.

**The system prompt** is a field, so the first one goes there wherever it was written. The rest stay
where they were, as `role: "system"` messages, on the models that take one in the middle of the
conversation — Opus 4.8, 5 and 5.5, Fable and Mythos 5 and 5.1 — which keeps the cached prefix intact. On the
others they are joined into the field, with a warning. The catalog says which is which, so there is
nothing to configure. The `dynamicSystem` of a request goes by the same rule: last, as a system message,
where the model takes it, and as a second block under the system prompt where it does not (see [What
changes goes last](#what-changes-goes-last)).

**Prompt caching is three independent flags.** Unlike OpenAI, which caches on its own, Anthropic caches
only up to a mark, and a later call reads the cache only if everything up to that mark is exactly the
same. Each flag puts a mark in a different place, and they add up:

| flag | where the mark goes | pays off when |
|---|---|---|
| `system` | at the end of the system prompt, before the `dynamicSystem` when it goes under it | the same long system prompt comes before a new question every time |
| `tools` | on the last tool | the tools stay the same and the system prompt does not |
| `conversation` | Anthropic's own mark, on the last block, moving forward as the conversation grows; on the block before the `dynamicSystem` when that one goes last | each call repeats the one before and adds a turn |

```kotlin
anthropic.cache = AnthropicCache(system = true, conversation = true)       // an agent or a chat
anthropic.cache = AnthropicCache(system = true)                            // one-off questions, long prompt
anthropic.cache = AnthropicCache(system = true, ttl = AnthropicCacheTtl.OneHour)
```

In `settings.json`, under `ai.providers.anthropic`: `"cache": { "system": true, "conversation": true }`.
A call can replace the whole setting with `AnthropicOptions(cache = ...)`.

**Anthropic reads the tools first**, then the system prompt, then the messages, so a mark on the system
prompt caches the tools too. `tools` is only worth it alone when the system prompt changes on every call.
What changes on every call, like today's date, is better in `dynamicSystem`, and the marks leave it out.

**`conversation` alone does not cache a shared prompt.** Its mark lands on the last block, which in a
one-off question is the question itself: new every time, so every call pays the higher price of a write
and none reads it back. A recording showed exactly that — the same 7,246-token system prompt written
twice, read zero times. The same two calls with `system` wrote 7,230 tokens once and read all of them
back the second time, paying the plain price only for the twenty tokens of each question. That is why
`system` exists, and why the two go together for an agent. The other
libraries that go beyond a manual mark — Spring AI, Pydantic AI, Laravel, LangChain, LiteLLM — mark the
system prompt for the same reason.

Everything is off by default: a write costs 1.25 times the input for five minutes and twice for an hour,
and only pays off when it is read back. Below the model's minimum, between 512 and 4,096 tokens depending
on the model, nothing is cached and nothing fails either. Anthropic takes four marks per request and
these use at most three; a cut somewhere else goes in the `ProviderMetadata` of the part it follows.

**Tool results are a turn of the user**, because a result is something the model is told. Anthropic
matches one to its call by id alone, so the name of the tool has nowhere to go. And it refuses a user
message that has anything before its results — *"tool_use ids were found without tool_result blocks
immediately after"* — so the adapter moves them to the front: that is a rule about the wire, and asking
the next question right after the answer is a reasonable way to build a conversation.

**A tool of the provider is named by the versioned type** Anthropic gave it, with its own name as an
argument, since a server tool carries both:

```kotlin
ProviderToolSpec("anthropic.web_search_20260209", Json.obj("name" to "web_search"))
```

**`AnthropicOptions`** is the way past the catalog. It is sent as it was written, without asking what the
model takes, so whoever knows their model gets exactly what they asked for: `effort` reaches the `xhigh`
and `max` levels `Reasoning` does not have, `thinking` names a budget or the adaptive mode, and there are
`cache`, `userId` and `serviceTier`.

### Thinking tied to the conversation

This is the strangest rule of the whole API, and nothing an application has to do about it: the adapter
does it. It is written down because it shapes everything that touches a conversation, and because it
fails in a way that is hard to trace back.

**What Anthropic does.** On Claude Opus 5.5 and Fable 5.1, each thinking block the model returns is tied
to everything that came before it when it was produced: the system prompt, the tools and every earlier
message. When the block goes back in a later call — the next step of a run, or the next turn of a chat —
the API checks that all of that is still exactly the same, and answers 400 when it is not:

> *Invalid `signature` in `thinking` block. The block is bound to a different conversation. [...] Content
> that preceded this block when it was created is missing from this request.*

It does it by default on accounts created from 31 August 2026 on, and on older ones only if a call asks for
it. The rule it lays down is that **on those models a conversation can only grow at its end**: nothing
already sent can be edited, moved or removed. Appending, and changing the settings of the call or the cache
marks, is fine.

**Why it collides with `dynamicSystem`.** The dynamic part goes after the conversation and is never a
message of it, so on every call it moves: the thinking of the last answer was produced with it right
before, and on the next call it is at the end instead. That is an edit, and the 400 comes on the turn after.
A recording showed exactly that: a run on Opus 5.5 went well, and the next question failed with the error
above, naming the dynamic part as what was missing.

**What the adapter does about it.** On those models the dynamic part goes as a system message that lasts
one turn: `clear_at: "next_user_message"`, a beta the adapter asks for itself. The model reads it until a
user message comes after it — a message with only tool results counts — and from then on it stays in its
place but reads as nothing and costs no tokens. The thinking of the answer remembers the dynamic part it
was produced with, in the `ProviderMetadata` of its part, next to the signature. On the next call the
adapter puts that copy back right before the answer, already cleared, so what came before the thinking is
exactly what it was. The cache reads everything but the new part, the model keeps its earlier reasoning,
and the dynamic part never becomes a message of the conversation.

The one thing it asks of an application is what the signature already asks: that the parts of a message
are stored with their metadata, as they came.

**When the past does change: a handoff.** When one agent hands the conversation over to another, the
next call goes out with other instructions and other tools, and the thinking of the agent before was
produced under the old ones. That thinking would be refused, and there is no way around losing it: the
new agent goes on without the reasoning of the old one, as it would with another model. What the adapter
makes sure is that it loses nothing else.

Each thinking block also remembers a fingerprint of the system prompt and the tools it was produced
under, in the same metadata. A call leaves out every thinking block up to the last one whose fingerprint
is not the one of the call, with a warning, and sends the rest. It is not only the blocks that changed:
Anthropic takes thinking left out from the start of the conversation, or from its end, but not from its
middle, so a block that still fits goes too when one after it did not. The blocks after the last one
that changed — the reasoning of the new agent — stay valid, and stay. A conversation that goes on with
the new agent, in the next turn or the one after, keeps leaving out the same old blocks and keeps all of
its own.

**What the other libraries do.** Most of them let the block go instead. The option for that is
`thinking.block_binding.prefix_mismatch_behavior`: with `"drop_block"`, Anthropic removes a block whose
past changed, and every thinking block after it, before the model reads the call; the call goes through,
but the model no longer sees what it reasoned there. Zed sends it on every call to those models;
PydanticAI sends nothing, retries once with it when the 400 comes, and warns; Goose makes it a setting.
It is not used here: after a handoff, "every thinking block after it" is all the reasoning of the new
agent, on every call of the conversation from then on. The one case left is a context policy that drops
old messages, which comes later.

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
