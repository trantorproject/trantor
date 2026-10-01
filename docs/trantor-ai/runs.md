# Runs: the facade and the tool loop

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
call to the model and the tools it asked for is a **step**.

The builder takes the messages in the order they are written — `system`, `user`, `messages(history)` —
and `dynamicSystem` for the part of the instructions that changes from one call to the next (see [What
changes goes last](models.md#what-changes-goes-last)). It also takes `model` (a reference or an alias, `default` when
left out), `tools`, `searchableTools` (see [Tool search](tools.md#tool-search)), `toolChoice`, `maxSteps`,
`settings { }`, `options(...)` for the provider, `context(RunContext)` for the tools and
`callOptions(...)` for the timeout and the cancellation, which reach every step.

## What a run gives back

```kotlin
result.text              // what the model answered last
result.steps             // each call to the model, with the results of its tools
result.usage             // added up over the steps, and the runs their tools made
result.estimatedCost     // added up too; null if any of them has no estimate
result.newMessages       // what the run added to the conversation, to keep it
result.toolFailures      // the exceptions of the tools that failed, for the application
result.paused            // whether it ended waiting for a person to approve some calls
result.pending           // those calls, when it did: see Approvals
```

`newMessages` is what to store to continue the conversation later: the answers of the model, whole, and
the results of the tools. A step whose calls were not run is left out of it, because a tool call without
its result is something every provider refuses.

## The history: where it is kept and what each call sends

Two things that are easy to mix up, and are kept apart: the conversation the application keeps, and the part
of it each call sends to the model.

```kotlin
ai.generate {
    system("Sos el asistente de una ferretería")
    session(session)
    user(question)
    contextPolicy(DropOldToolResults(keep = 10), LastMessages(40))
}
```

A `Session` is where the conversation is kept between runs: `load()`, `append(messages)`, and `replace(messages)`
for a [compaction](#compacting-the-old-part). The builder
reads it where `session(...)` is written, since the request goes in the order it is written, and once the
run ended well the session keeps what came after it in the request and what the run added. What comes
before it, like the system prompt, is not kept, and a run that fails keeps nothing. `InMemorySession` is
for tests and prototypes; an application implements `Session` over its own tables, keeping each message
whole, metadata included. The dynamic part of the instructions is not a message, so it never gets there.

A session reads the whole conversation. How much of it goes to the model is up to a `ContextPolicy`, a
function from the conversation to what one call sends, asked before every call, a run with tools included.
It never touches what is kept. The system messages the conversation starts with always go, and a policy
gets the rest; several go in the order given.

- `LastMessages(max, step)` sends the last `max` messages at most, starting at something the user said.
- `DropOldToolResults(keep, step)` sends the old results of the tools as a short note and the last ones
  whole.

Both move `step` messages or results at a time, not one: a window that moves with every message changes
what goes first on every call, and the providers cache what goes first. A policy of the application should
do the same. A policy that leaves a call without its result, or a result without its call, does not break
the call: the loop takes the half left alone out, with a warning, since every provider refuses it.

A policy bounds what each call sends, not what is kept; what bounds that is to [compact](#compacting-the-old-part)
the old part. Two runs on the same session at once would read the same history and add each their own: the
application runs the turns of a conversation one at a time.

## Compacting the old part

A compaction replaces the old part of the conversation with a summary of it, so that what is kept and what is
sent stop growing. Unlike a policy, it changes the conversation itself: what it leaves is what is kept from then
on.

```kotlin
ai.generate {
    session(session)
    user(question)
    compaction(SummaryCompactor(ai.models().chat("fast")), afterTokens = 60_000)
}
```

**When.** Once the run ended well, before it is kept, if its last call to the model went past `afterTokens`:
its input, with what came from the cache, and its output, which is how big the next call starts. Pick it well
below the context window of the model. A run of the agents takes the same `compaction(...)`.

**What.** The conversation the run keeps: what the session had, or the history the run was given, and what it
added. Never the system prompt written before `session(...)`, nor the instructions of an agent, which are not
kept either. With a session it is kept with `Session.replace`, and what to do with the old rows — delete them or
mark them replaced — is up to the application. Without one, `result.compacted.conversation` is the history to
keep instead of the one the run was given.

**How.** `SummaryCompactor(model, keepTurns = 2, instructions)` keeps the system messages the conversation starts with
and its last `keepTurns` turns as they are — a turn starts at something the user said, so a call is never cut from its
result — and summarizes everything between them, the summary of a compaction before included. The model reads the old
part told line by line, who said, called and got what, and not the turns themselves: any model of any provider can
summarize a conversation of any other, a cheap one included. The default instructions ask to keep facts, names, numbers,
dates, decisions and what is still pending, without a title; `instructions` replaces them, to say what matters to the
application. A `Compactor` of the application can summarize any other way.

The summary is a `Message.Summary`, which goes first, after the system messages. No policy cuts it. Every provider
reads it as something the user tells, after a line that says it is a summary of what came before.

**When it fails.** A model that writes no summary, or one cut short, and any other error, leave the run as it
ended: the conversation is kept as always, with a warning in `result.warnings` that says why, and the next run
tries again. A compaction never fails a run.

**What it costs.** The call that wrote the summary is part of the run that compacted: `result.usage` and
`result.estimatedCost` count it, and a model from the registry goes through the middlewares, `CostMiddleware`
included. It reads the old part without the cache of the conversation, since it reads it told and not as it was
sent. A stream compacts once it is read to its end, where it keeps its conversation, and not when it is closed
before.

**What is lost.** Whatever the summary did not keep, which is why the last turns stay whole. On the Claude models
that tie their thinking to what came before it, the thinking of the turns kept is left out from then on, with a
warning, as it is after a policy cut. Anthropic and OpenAI have compactions of their own, which keep that thinking
and read from the cache; Trantor does not use them yet.

With an `openTelemetry`, `SummaryCompactor` traces its call as a `chat` inside the span of the run, and every call
that carries a summary says `gen_ai.conversation.compacted` (see [Telemetry](telemetry.md#telemetry)).

## Objects

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

The schema is the one the serializer of the application tells for `T`, and the answer is read by that same
serializer, so the object can have the types of the domain: an id, `Money`, a value object the application
registered, a hierarchy. See [The schema of what it reads](../trantor-gson.md#the-schema-of-what-it-reads).

## Steps and their limit

A run takes at most `maxSteps` calls to the model, 10 by default. Past them it throws
`MaxStepsExceededError`, with what the run did so far in `error.result`. The calls of that last step are
**not** run: no model would read their results, and a tool with effects would have them all the same.

The limit fails instead of handing back what there is: a use case that expects a text would get an empty one
without noticing.

## Streaming a run

```kotlin
ai.stream { user(question); tools(products) }.use { stream ->
    stream.textDeltas().forEach { print(it) }

    val result = stream.result()
}
```

A `RunStream` is the same run received as it happens, as `RunEvent`s: `StepStarted`, `Model` with what
the model produces (a `StreamPart`, as the adapter read it), `ToolStarted`, `ToolFinished` and
`StepFinished`. A step whose calls run at the same time says they all started before any of them finished.
A run of agents also has `Handoff(from, to)`, right after the step that handed the conversation over, and
`GuardrailTripped(guardrail, reason)` as its last event when a guardrail stops it; a generation has neither, since
it has nobody to hand the conversation to and no guardrails.

A run that [pauses](tools.md#approvals) says `ApprovalRequested(pending)` for each call that waits, after the tools of
the step that did run and before its `StepFinished`, and the stream ends there. One that picks the conversation up
starts with the calls it answered: `ToolStarted` and `ToolFinished` for the approved ones, and `ToolNotApproved` for the
others, before its first step.

`RunEvent` is sealed, so a `when` over it has to name every case; a new kind of event is a change to compile
against, which is why `else` is worth it where only some of them matter.

`result()` consumes whatever is left and gives the `RunResult`, and closing the stream closes the call in
flight and runs no more tools.

## Without the facade

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
