# Models

The layer under runs and agents: one call to a model, one answer. A run and an agent are built on it, and
an application uses it directly for whatever is not a run.

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
| Anthropic, a model that ties its thinking to the conversation (Opus 5.5, Sonnet 5.5, Fable 5.1) | a system message after the conversation that lasts one turn, with the earlier ones put back where they were (see [Thinking tied to the conversation](providers.md#thinking-tied-to-the-conversation)) |

It is not a message of the conversation: it never shows up in `newMessages`, and a run sends it again on
every step. The facade has it as `dynamicSystem(...)`.

This is what both providers recommend. OpenAI's prompt caching guide puts the *"dynamic developer
instructions, such as user-specific content and timestamps"* after the stable ones, and Anthropic added
system messages in the middle of the conversation because editing the top one *"invalidates the cache for
everything that follows"*.

## The shapes

**Naming**: a name shared across model families carries no prefix — `CallOptions`, `Usage`,
`ResponseInfo`, `ModelWarning`. A name that belongs to chat carries it — `ChatModel`, `ChatRequest`,
`ChatResponse`, `ChatStream`.

**Parts**: `TextPart`, `ReasoningPart`, `ToolCallPart`, `ToolResultPart`, `RefusalPart`, and
`ProviderPart` for anything the adapter did not recognize. A `ProviderPart` carries the original payload
and goes back where it came from, so a field the provider added is visible instead of lost.

**A refusal says why, where the provider does.** When the safeguards of Claude decline a request, the answer can be
empty but for the stop reason and its details; the `RefusalPart` then carries the explanation as its text and the
policy area as its `category` — `cyber`, `bio`, `frontier_llm`, `reasoning_extraction`, `general_harms` — which tells
an application whether trying another model or another prompt is worth it. `reasoning_extraction` is the one a prompt
of the application can bring about: asking the model to write out its reasoning in the answer, like a system
prompt that asks for a detailed note of what was found and meant after each step.

**Warnings, not silence.** When a request asks for something the provider cannot do, the response carries
a `ModelWarning`. `failOnWarnings` turns those into an error for an application that would rather stop.

**Usage is totals and their parts.** `inputTokens` and `outputTokens` are totals. The input splits into
`uncachedInputTokens`, `cacheReadTokens` and `cacheWriteTokens`, which add up to it, and `reasoningTokens`
is part of the output. Each part is named for what happened to those tokens because each is priced apart;
"cached" alone would not say whether it was read or written. Null means the provider did not say.

## Structured output

```kotlin
data class Invoice(val number: String, val total: Money)

val response = models.chat().generate(
    ChatRequest(listOf(Message.user("Extraé los datos")), output = OutputSpec.json<Invoice>(serializer)),
)

val invoice = response.objectAs<Invoice>(serializer)
```

`serializer` is the `JsonSerializer` of the application, which tells the schema of `Invoice` and reads the answer
back by the same rules. It has to be a `JsonSchemaSource`, as the `GsonSerializer` is.

The schema is closed before it is sent: every property required, no open maps. Both providers ask for the same
thing — OpenAI calls it strict mode, Anthropic only demands `additionalProperties: false` — so the shaping is shared
in `StrictSchema`. What each holds a model to differs, as their docs list it: Anthropic takes no bound on a number
or on the length of a string, OpenAI no length of a string. What a provider does not take goes to the description
of its field (`{minimum: 1, maximum: 10}`), where the model reads it without being held
to it; whoever reads the answer still checks it. A `oneOf` goes as `anyOf`, which is what both take. A schema that
refers to itself is one Anthropic cannot hold a model to: a tool goes without strict, with a warning, and an answer
is turned down with `UnsupportedRequestError` before anything is sent.

Which wire field carries it is the adapter's business and not the caller's: `text.format` on OpenAI,
`output_config.format` on Anthropic. A model too old to have one drops it with a warning.

## Streaming a call

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

A stream cut before the end still answers, with what
arrived and a warning saying so.

A tool call is the one thing that cannot be handed over early: its input travels as pieces of text that
are not json until the last one arrives, so it comes whole in a `PartDone` or not at all.

A run, with its tools, is streamed with `ai.stream`: see [Streaming a run](runs.md#streaming-a-run).

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

`Reasoning.Off` is not sent to a model that always thinks, like Claude Opus 5.5, Fable and Mythos: the
warning says that a lower effort is how they think less. On Sonnet 5.5 it becomes `between_tools`, its lowest
thinking, which does not think before answering and only writes short notes between tool calls.

Reasoning content the provider will not show comes back as an opaque `ReasoningPart`, which is passed
back on the next turn so the model keeps its own chain. It is signed or encrypted per provider, so an
adapter only takes back reasoning its own provider produced.

### Notes between tool calls

Claude Fable 5.1, Mythos 5.1, Opus 5.5, Sonnet 5.5 and Fable 5 write, between tool calls, a note for whoever watches
the run: what they found and what they will do next. A short one is text, like any other; a longer one comes back as a
thinking block of its own, which by default is empty, so an interface that shows the text goes quiet between the
calls.

So on those models, when the application did not ask to see the reasoning, the adapter asks for the notes apart from
it (`display: "updates"`, a beta of Anthropic): the thinking blocks stay empty and the notes come with their text.
A note is a `ReasoningPart` with `note = true`, whose `text` is the note, and in a stream it arrives as
`StreamPart.NoteDelta` — in a run, inside `RunEvent.Model` — instead of `ReasoningDelta`. It goes back to the provider
like any thinking. `Reasoning.Off` on Sonnet 5.5 (`between_tools`) brings the notes with their text too.

Asking for a summary of the reasoning (`ReasoningSummaries.Auto`) gets it, and then a note cannot be told from the
summary: both are `ReasoningPart`s with text. What `AnthropicOptions.thinking` asks for goes as it was written.

```kotlin
stream.forEach { event ->
    when (val part = (event as? RunEvent.Model)?.part) {
        is StreamPart.TextDelta -> show(part.text)
        is StreamPart.NoteDelta -> showStatus(part.text)
        else -> {}
    }
}
```

## Middlewares

`ChatModelMiddleware` wraps any model, with `transform`, `generate` and `stream`:

```kotlin
models.use(RetryMiddleware())
```

`RetryMiddleware` tries again only when the provider said the problem was temporary — a rate limit, a
5xx, a timeout, a broken connection. A bad key, a rejected request or a body that did not parse fails on
the first try. The call's `timeout` is a **deadline for the whole thing**, not for each attempt, and each
attempt gets what is left. A stream is reopened only while nothing has reached the caller.

`CostMiddleware` puts what the call probably cost in `response.info.estimatedCost` — see [Cost](catalog.md#cost).
It takes the catalog the providers share, so the container builds it:

```kotlin
services.addAI { models, services ->
    models.use(RetryMiddleware())
    models.use(services.create<CostMiddleware>())
}
```

Middlewares wrap in the order they were named.

## Cancellation

```kotlin
val cancellation = Cancellation()

models.chat().generate(request, CallOptions(cancellation = cancellation))
```

`Cancellation` lives in `trantor-primitives` (`dev.botta.trantor.primitives.Cancellation`), since the http
client takes it too; `throwIfCancelled()`, which fails with `CancelledError`, is an extension of this module.

The cancellation goes to the http client with the call, which cuts it at any point: while it waits for the
answer, which is the whole call when the answer is not streamed, or while it is read. A call cut while it
waits fails as `CancelledError`, not as a provider error. One cut while it is read ends as if the provider
had stopped writing, so what was read is checked afterwards: half an answer is a `CancelledError`, never an
answer. The client stops listening when the call ends, so a token reused across calls does not accumulate
callbacks.
