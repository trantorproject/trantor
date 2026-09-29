# trantor-ai

Access to LLM providers behind one contract, and a facade for use cases that runs the tools a model asks
for, and agents that run on the same loop. The model layer is one call, one answer; the facade, its tool loop
and the agents are built on top of it.

---

## Registering it

```kotlin
services.addAI()
```

That brings `AI`, the `AgentRunner`, the `ModelRegistry` and the providers Trantor ships with. Middlewares
are named by the application, never collected from the container:

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

The providers call through the `HttpClient` of the application (see
[trantor-web-client](trantor-web-client.md)), and add it when there is none: one pool of connections for
everybody, and the calls to OpenAI and Anthropic are traced like any other. A model can think for a while
before its first word, so how long a call waits in silence is a setting of the provider, `readTimeout`
(two minutes by default), which every call sets on its own. The `requestTimeout` of the client does not
apply, and the whole call is bounded by the `timeout` of `CallOptions`. A client the application registers
instead has to stream, as the one of Trantor does.

With an `OpenTelemetry` in the container, every generation and every run of the agents is traced and measured;
see [Telemetry](#telemetry).

The MCP servers whose tools the application uses are registered apart, with `addMcp()`: see [MCP](#mcp).

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

### The history: where it is kept and what each call sends

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

### Compacting the old part

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

**How.** `SummaryCompactor(model, keepTurns = 2, instructions)` keeps the system messages the conversation starts
with and its last `keepTurns` turns as they are — a turn starts at something the user said, so a call is never
cut from its result — and summarizes everything between them, the summary of a compaction before included. The
model reads the old part told line by line, who said, called and got what, and not the turns themselves: any model
of any provider can summarize a conversation of any other, a cheap one included, which is what ADK, LangChain and
Microsoft do. The default instructions ask to keep facts, names, numbers, dates, decisions and what is still
pending, without a title; `instructions` replaces them, to say what matters to the application. A `Compactor` of the application
can summarize any other way.

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
that carries a summary says `gen_ai.conversation.compacted` (see [Telemetry](#telemetry)).

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

A tool that calls a model itself takes `context.callOptions`, the timeout, the cancellation and the headers
of the run, so that cancelling the run stops that call too; and it answers `ToolResult.text(...).withRun(run)`
with the run it made, which stays in the step (`step.toolRuns`) and whose usage and cost count as the run's.
Otherwise the run spends without anyone seeing it. An agent that runs as a tool does both on its own.

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

### Approvals

A tool can ask for a person to approve a call before it runs:

```kotlin
class RefundTool(private val payments: Payments): Tool<RefundTool.Args>(Args.serializer()) {
    override val name = "refund"
    override val description = "Gives back the money of an order, in dollars"

    override fun needsApproval(args: Args, context: ToolContext) = args.amount > 100

    override fun execute(args: Args, context: ToolContext) = ToolResult.text(payments.refund(args.order, args.amount))

    @Serializable
    data class Args(val order: Int, val amount: Int)
}
```

`needsApproval` gets the args as the model sent them, before any hook changes them, which are the ones the person
sees. Args that do not fit the tool are not asked about: the call goes back to the model as an error, as always.
A [tool guardrail](#guardrails) can ask for approval too, for rules that depend on who the run is for.

**A call that waits does not run, and the run ends there.** The other calls of the step run, the model is not
called again, and the result says so:

```kotlin
val result = ai.generate { session(session); user(question); tools(orders, refund) }

if (result.paused) {
    result.pending          // each call that waits: the call (its id, tool and args), the agent, the reason
    notifyWhoApproves(result.pending)
}
```

The session keeps the paused run like any other: the answer of the model with all its calls, the results of the
ones that ran, and nothing for the ones that wait. That is where the pause lives: **the call without its result
is the mark**, and there is no other store. A paused run is not [compacted](#compacting-the-old-part), since it is
not over, and its `text` is whatever the model said before asking.

**Picking it up is another run on the same conversation**, with what the person decided:

```kotlin
ai.generate {
    session(session)
    tools(orders, refund)
    decisions(Approve(callId), Reject(otherCallId, "The customer changed their mind"))
}
```

Before it calls the model, the run answers the calls the last answer left without a result:

- **An approved call runs**, with the tools and the hooks of the run, without asking for approval again.
- **A rejected one** does not run, and the model reads the message of the decision as its error. Without one, it
  reads that the call was not approved, that it should not call it again and that it should tell the user: told
  only that it was not approved, o4-mini asked for it again right away when the user had asked for it. A message
  of the application runs the same risk.
- **One without a decision** is answered as not approved, with a warning. That is what happens when the user
  writes something else instead: the conversation never keeps a call without its result, which every provider
  refuses.
- **A decision about a call that is not waiting** fails the run with `NoPendingCallError` before anything runs.
  It is what a decision sent twice meets, since the first one answered the call: a call is never run twice.

The results go right after the answer that made the calls, before a message the user wrote after it, which is
where the providers take them; the session keeps them there. Without a session, `newMessages` starts with them,
and the application puts them before the messages it gave the run. `result.resolved` has what the run answered,
and its usage, failures and warnings count as the run's. A picked-up run can pause again.

Some things to know:

- **Notify whoever approves once the run returns**, as above, when the session already keeps the pause. An
  `afterRun` hook of the agents hears of a paused run too, but it runs before the session keeps it.
- **The pause is as safe as the conversation.** With a session, what waits is read from the server, and the client
  only sends ids and decisions; the one who decides is authenticated and authorized by the application, and the id
  of a call is not a permission. Without a session, a client that sends the history can approve a call it made up,
  which OpenAI Agents and PydanticAI warn about too. A tool that does something sensitive still authorizes it.
- **One run at a time on a conversation**, as always: two decisions at once would both find the call waiting.
- **In a job**, a session per job, and the decision is another job that picks it up.
- **The last step allowed**: if every call of it waits, the run fails with `MaxStepsExceededError`, as it would
  with calls that could not run.
- **Not yet:** changing the args when approving, approving a tool for the rest of the conversation, and a call
  answered by someone else, like the front end.

The pause lives in the conversation, as in AI SDK, Microsoft Agent Framework, PydanticAI and ADK, and not in a run
state stored apart, as in OpenAI Agents, Mastra and LangGraph. There is one thing kept instead of two that have to
agree, and nothing serialized that a deploy could break while a run waits; what that gives up is a single run
across the pause, whose usage and steps are two results here. What the traces show of it is in
[Telemetry](#approvals-1).

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
A run of agents also has `Handoff(from, to)`, right after the step that handed the conversation over, and
`GuardrailTripped(guardrail, reason)` as its last event when a guardrail stops it; a generation has neither, since
it has nobody to hand the conversation to and no guardrails.

A run that [pauses](#approvals) says `ApprovalRequested(pending)` for each call that waits, after the tools of the
step that did run and before its `StepFinished`, and the stream ends there. One that picks the conversation up
starts with the calls it answered: `ToolStarted` and `ToolFinished` for the approved ones, and `ToolNotApproved`
for the others, before its first step.

`RunEvent` is sealed, so a `when` over it has to name every case; a new kind of event is a change to compile
against, which is why `else` is worth it where only some of them matter.

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
tools of the step that got it, and the tools are asked for their description again on every step. A `StepSetup` can
also name the agent the step goes out as: its answer is kept signed with it, in `Message.Assistant.agent`, so that
later an agent can tell its own turns from those of another. The application keeps it with the message. It can
also bring `StepHooks`, which the loop calls around the model and the tools of that step: before the call, with the
request it can change; after it; before each tool, with args it can change; and after each tool. Before any call of
the step runs, it is also asked about each of them, as the model made it: it can refuse one, which the model then
reads as an error and the run keeps as a warning, or throw, which fails the run with no call of the step half done.
It is how the hooks and the tool guardrails of an agent reach the loop.

---

## Agents

An agent is a model with a role: its instructions, its tools, the agents it can hand the conversation over to,
and how it answers. It is a value, built once and shared by every run, and the `AgentRunner` that `addAI()`
registers runs it on the same tool loop a generation runs on: the steps, `maxSteps`, the failures of the tools
and the stream are the ones above.

```kotlin
val support = Agent("support")
    .model("smart")
    .instructions("Sos el soporte de una ferretería")
    .dynamicInstructions { run -> "El cliente es ${run.require<Customer>().name}" }
    .tools(products)
    .build()

val result = agents.run(support, Message.user(question)) {
    context(RunContext(customer))
    maxSteps(12)
}
```

The agents of an application are built by a factory of the application, a plain class with its dependencies in
the constructor; the framework asks for no interface. What changes with the tenant or the run is code of that
factory, or a function of the `RunContext` in the instructions.

- `instructions` go first, as a system message the conversation does not keep, and `dynamicInstructions` last,
  for what changes from one call to the next (see [What changes goes last](#what-changes-goes-last)). Each is a
  string or a function of the `RunContext`, asked on every step, so what a tool changed is read on the next one.
- `model` is a reference or an alias, `default` when left out, or a `ChatModel` in hand. `settings { }` and
  `options(...)` go on every call the agent makes; an option for another provider is left out with a warning.
- `with(value)` carries a value of the application, like the id of an assistant, which a hook or a tool gets back
  by its type: `context.agent.require<AssistantId>()`. One value per type, so a `String` is best given a type of
  its own.

### Running one

`agents.run(agent, conversation) { }` takes the conversation so far, whose last message is usually what the user
just said, and in its block what belongs to that run and not to the agent:

| Option | What it is |
|---|---|
| `team(...)` | The agents it can hand the conversation over to, besides the one it starts with |
| `maxSteps(n)` | Calls to the model, whichever agent makes them; 10 by default |
| `context(RunContext)` | Who the run acts for, for the instructions and the tools |
| `options(...)` | Options of a provider for this run, like the key that keeps its cache together. They go after the agent's, so the run wins |
| `callOptions(...)` | Timeout, cancellation and headers, for every call of the run |
| `session(...)`, `contextPolicy(...)` | Where the conversation is kept and what each call sends of it: [below](#the-history-of-a-conversation-with-agents) |
| `hooks(...)`, `inputGuardrails(...)`, `outputGuardrails(...)`, `toolGuardrails(...)` | For this run only, after the global ones and the agent's |
| `decisions(...)` | What a person decided about the calls a paused run left waiting: [Approvals](#approvals) and [in a team](#approvals-in-a-team) |

An `AgentRunResult` has what a `RunResult` has — `text`, `newMessages`, `usage`, `estimatedCost`, `warnings`,
`toolFailures`, `paused`, `pending` — plus the agent of each step in `steps`, the one that answered in
`lastAgent`, and a `runId` that its tools and hooks see too. `result.result` is the `RunResult` underneath.

### Answering an object

```kotlin
val triage = Agent("triage").output<Ticket>().build()

val ticket = agents.run(triage, Message.user(text)).output<Ticket>()
```

There are two ways of asking the model for the object:

- `OutputMode.Native`, the default: the schema goes as the format of the answer on every step. The model calls
  its tools with the format set and answers the object once it is done with them. It takes a model that does
  structured output.
- `OutputMode.Tool`: the object is the args of one more tool, `final_result`, and the run ends when the model
  calls it. It works with any model that takes tools. A model that answers text instead is reminded once to call
  it.

`output<T>()` fails with `NoObjectGeneratedError` when the answer is not the object.

### One agent using another

The first thing to reach for when a job needs a specialist: the agent that has the conversation calls another as
a tool, the other runs on what it was asked, and its answer is the result of the call.

```kotlin
val researcher = Agent("researcher")
    .instructions("Buscás el clima y el precio del paquete de un destino. Respondé solo con los datos.")
    .tools(weather, prices)
    .build()

val writer = Agent("writer")
    .instructions("Escribís notas de tres oraciones para el newsletter.")
    .tools(researcher.asTool(agents, "Finds the current weather and the price of a package to a destination"))
    .build()
```

The model calls it with a `task`, and the description of that arg tells it the agent sees nothing of the
conversation, so the task has to carry what it needs. The run of the agent:

- **gets only the task**, as a message of the user: not the conversation, nor the session, nor the context policy.
  It is what OpenAI Agents, ADK, Microsoft and AI SDK do.
- **runs for whom the run that called it runs**: the same `RunContext` and call options, so cancelling one cancels
  both. The global hooks and guardrails apply to it; those of the run that called it do not. The block of
  `asTool { }` sets its options, like `maxSteps`.
- **answers** its text, or its object as JSON when it has an `output<T>()`.
- **spends for the run**: its run stays in the step that called it, `step.toolRuns`, and its usage and cost count as
  the run's.
- **fails like any tool**: the model reads `Tool execution failed`, and the exception stays in `toolFailures`.
- **goes at most `maxDepth` deep**, 3 by default, counting the agents that run as tools inside each other. Past it
  the call goes back to the model as an error, which is what ends a cycle of agents that use each other.

Its events do not reach the stream of the run that called it, which shows it as a tool that starts and finishes.

The conversation stays with one agent, each specialist sees only what it is asked, and no model has to make sense
of what another one did. What it cannot do is let the specialist talk to the user: that is a handoff. The task is
written by the model that calls, so its instructions say what the specialist is for and what not to make up: in the
recordings, the writers added details of the package that nobody had given them.

### Handing the conversation over

When the specialist stays with the conversation — from then on the user talks to sales — the agent hands it over:

```kotlin
val support = Agent("support").tools(weather).handoffs("sales").build()
val sales = Agent("sales").tools(prices).build()

val result = agents.run(support, Message.user(question)) { team(sales) }
result.lastAgent                           // sales, if support handed over
```

There are two ways, and the runner has one path for both:

- **Declared**: `handoffs("sales")` gives the agent a tool `transfer_to_sales`; `handoff("sales", description)`
  tells the model when to use it.
- **From a tool of the application**, when handing over is part of an operation, like assigning the conversation to
  the sales team: the tool does it and answers `ToolResult.text("Asignada a ventas").handoffTo("sales")`.

The rules:

- **The team** is the agent the run starts with and those of `team(...)`. Before calling any model the runner fails
  if two agents have the same name or one declares a handoff to someone outside the team, which would be a tool
  that always fails.
- **The agent changes when the step is over.** Every call of the step runs with the tools of the agent that asked
  for it, and the next step goes out with the new one.
- **The first handoff of a step wins.** A second one in the same step ran, with its effects, but the model reads an
  error and the run gets a warning. A handoff to someone outside the team is an error that names the team, and one
  in `ai.generate`, which has no team, is ignored with a warning.
- **Agents handing over back and forth** end on `maxSteps`, which counts the steps of every agent.
- **The next turn** starts with the agent the application picks, usually `result.lastAgent`, whose name it keeps
  with the conversation.

**How the new agent reads what the others did.** It gets the whole conversation, but the turns of other agents are
told, not sent as its own. Every answer is kept signed by the agent that wrote it, in `Message.Assistant.agent`, and
on every step the turns of another agent go as a message of the user that says, line by line, what it said, which
tools it called with what, and what they answered:

```
For context, this is what other agents of your team said and did in the conversation before it came to you.
It was not you, and their tools may not be yours:
[support] called getWeather with {"city":"Bariloche"}
[support] got from getWeather: {"celsius":7}
[support] called transfer_to_sales with {}
```

Its reasoning does not go, and what is kept does not change. It is what Google ADK does. Sent as they were, the
recordings showed the new agent taking the turns of the one before as its own in four runs out of five, calls to
tools it did not have included. So the application keeps the agent of each message along with it; an answer
without one goes as it is.

Since nothing of the other agent's provider travels, support can run on OpenAI and sales on Anthropic. A small
model may still ignore what it was told: as sales, o4-mini used the temperature support got in two recordings out
of five, reasoning that it had no tool for the weather, while the Claude models used it every time. When what the
first agent found matters, the agent that answers is better off asking for it as a tool.

### Several at once

Agents that work at the same time and whose answers are put together are code of the application, with a virtual
thread each:

```kotlin
val call = CallOptions(cancellation = cancellation)

val (weather, prices) = Executors.newVirtualThreadPerTaskExecutor().use { executor ->
    val weather = executor.submit(Callable { agents.run(meteorologist, Message.user(task)) { callOptions(call) } })
    val prices = executor.submit(Callable { agents.run(seller, Message.user(task)) { callOptions(call) } })

    weather.get() to prices.get()
}
```

Giving every run the same `Cancellation` is what lets one cancel stop all of them, and their usage is added up by
whoever joins them.

### Hooks

```kotlin
class LogTools: AgentHooks {
    override fun afterTool(result: ToolResultPart, failure: ToolFailure?, context: AgentToolContext) {
        logger.info("${context.agent.name} ran ${result.toolName}")
    }
}

services.addAgentHooks { hooks, services -> hooks.add(services.create<LogTools>()) }
```

`AgentHooks` are called around a run: `beforeRun`, `beforeModel` and `afterModel` around each call to the model,
`beforeTool` and `afterTool` around each tool, and `afterRun`. A hook watches, and two of them can change what
goes: `beforeModel` returns the request of that call, and `beforeTool` the args of that tool, which are decoded
like the model's own. Stopping a run is not for hooks but for guardrails.

- They are declared globally with `addAgentHooks`, on the agent and on the run, and called in that order, each
  getting what the one before returned. The agent's are those of the agent that has the conversation, so they
  change with a handoff.
- An exception in a hook fails the run. The calls of a step whose tools only read run at the same time, so
  `beforeTool` can be called from several threads.
- `afterRun` is called once the run ended well: not for a run that fails, nor for a stream closed before its end.

### Guardrails

A guardrail is a check that can stop a run. There is one kind for each thing a run can go wrong on:

- **`InputGuardrail`**, on the conversation, before the first call to the model: a request it should not take
  costs no call and runs no tool. OpenAI Agents runs them at the same time as the agent by default; here they
  always come first.
- **`OutputGuardrail`**, on the final answer, once the run ended well. Only the final answer: the text of an agent
  before it calls a tool, or of one that handed over, is not checked.
- **`ToolGuardrail`**, on each call the model asks for, with its args as the model sent them. Every call of a step
  is checked before any of them runs, so a trip leaves no step half done. Besides passing or stopping the run, it
  can `Reject(message)` a single call: the call does not run, the model reads the message as its error, and the
  run goes on with a warning. Or it can `AskForApproval(reason)`: the call waits for a person, as when its tool
  [asks](#approvals), with the reason in `pending`.

```kotlin
val offTopic = InputGuardrail("off-topic") { _, conversation ->
    if (classifier.isAboutTheStore(conversation.last())) GuardrailVerdict.Pass
    else GuardrailVerdict.Trip("It is not about the store")
}

val support = Agent("support").inputGuardrails(offTopic).build()
```

A guardrail that trips ends the run with a `GuardrailTrippedError`, which says which one, of what kind, why, and
what else it found (`details`), and carries what the run left, so nothing spent is lost. They are declared globally
with `addGuardrails`, on the agent and on the run, and asked in that order: the first that does not pass decides.
Except one that asks for approval: the ones after it are still asked, since approving the call would otherwise skip
them, so one that rejects the call or trips wins, and the call waits with the reasons of every one that asked. The
run that picks it up runs it once approved without asking them again.
The agent's are those of the agent it concerns: the one the run starts with for the input, the one that answered
for the output, the one whose step asked for the call for the tools. A check that calls a model costs a call each
time it is asked.

In a stream, the text of the final answer is held back until the output guardrails pass, since a check of safety
that lets the text out first is no check at all. One that only checks quality says `holdsText = false`, and the
text comes out as it is written.

### The history of a conversation with agents

A run takes a `session(...)` and a `contextPolicy(...)`, which work as they do for a generation ([The
history](#the-history-where-it-is-kept-and-what-each-call-sends)): the conversation is what the session holds and
then the messages the run got, and the policy decides what each call sends of it. A few things are particular to
agents:

- The session keeps what the run added only once it ended well: after the output guardrails and `afterRun`, so a
  run that fails or trips keeps nothing.
- The instructions of an agent are not messages of the conversation, so they are never kept.
- The session keeps each answer with the agent that wrote it, which is what the next run needs to tell the turns
  of the others.
- A `compaction(...)` runs after the output guardrails, and `afterRun` sees the result with the conversation
  compacted.
- A run that [paused](#approvals) is kept too, and heard of by `afterRun`, but not checked by the output
  guardrails nor compacted: it has no final answer yet.

### Approvals in a team

A paused run of agents works as [one of a generation](#approvals), with a few things of its own:

- **It picks up with the agent that made the calls.** After support handed the conversation over to sales and sales
  asked for a refund, `agents.run(support) { team(sales); decisions(...) }` goes on as sales, with its tools and its
  hooks: the answer that made the calls says who made them. That agent has to be the one of the run or in its team,
  or the run fails before anything runs. OpenAI Agents does the same with the agent of its `RunState`.
- **A handoff in the step that paused is not made**: the model reads that it was not, since the run that picks the
  conversation up could not tell, and can hand it over again then. Every other call of the step runs.
- **An approved handoff hands the conversation over** before the first step, and a stream says `Handoff`.
- **`output()` of a paused run** fails with `NoObjectGeneratedError`, saying it is waiting for approval.
- **An agent used as a tool** can be approved before it runs, with a tool guardrail on the name of its tool. A call
  that waits inside its own run is not supported: the run that called it cannot pause in its place, so it fails with
  `NestedApprovalError`.
- **In a stream with the text held back** by the output guardrails, the text of the step that paused comes out
  before its `ApprovalRequested`.

### Streaming a run of agents

```kotlin
agents.stream(support, Message.user(question)) { team(sales) }.use { stream ->
    stream.textDeltas().forEach { print(it) }

    val result = stream.result()
}
```

The events are those of a generation, with a `Handoff(from, to)` between the last step of an agent and the first
of the next. When a guardrail stops the run, its last event is `GuardrailTripped`, and reading on throws the
`GuardrailTrippedError`.

---

## MCP

An MCP server offers tools that any application can use: GitHub's issues, a database, the files of a folder.
trantor-ai has a client of its own, over HTTP and over stdio, and the tools of a server go into a generation or an
agent like any other tool. It speaks the 2026-07-28 revision of the protocol, where every request stands on its
own, and the revisions before it, which most servers still speak.

### Declaring the servers

```json
{
  "ai": {
    "mcp": {
      "servers": {
        "github": {
          "url": "https://api.githubcopilot.com/mcp/",
          "headers": { "Authorization": "Bearer ${GITHUB_TOKEN}" }
        },
        "files": {
          "command": ["npx", "-y", "@modelcontextprotocol/server-filesystem", "/data"],
          "env": { "FILES_KEY": "${FILES_KEY}" }
        }
      }
    }
  }
}
```

```kotlin
services.addMcp()
```

A server has a `url`, and is called over HTTP with its `headers`; or a `command`, and is started as a process and
called over its standard input and output, with its `env` and `workingDirectory`. `requestTimeoutSeconds` bounds
each request of either.

**A secret goes as a reference to the environment**, which the configuration resolves when it is read (see
[Interpolation](trantor-config.md#interpolation)). A header or a variable of a process has a name the environment
provider cannot write, like `Authorization`, so the value cannot come from the environment on its own.

`addMcp()` registers `McpClients`, with a client for each server, and is idempotent. The application adds clients
of its own in code, whichever order the calls go:

```kotlin
services.addMcp { clients, _ -> clients.add(McpClient.http("crm", crmUrl, mapOf("X-Api-Key" to crmKey))) }
```

**A client connects to nothing until it is used**, so a server the application declares and never asks for costs
nothing, and a server that is down does not keep the application from starting. The clients over HTTP call
through the `HttpClient` of the application, which `addMcp()` adds when there is none. When the application stops,
every client is closed: a server over stdio is a process, and it would outlive the application otherwise.

### Their tools in a run

```kotlin
class SupportAgents(private val mcp: McpClients) {
    fun support() = Agent("support")
        .instructions("Sos el soporte de la aplicación")
        .tools(
            *mcp["github"].tools(
                only = setOf("search_issues", "get_issue", "create_issue"),
                readOnly = setOf("search_issues", "get_issue"),
                needsApproval = setOf("create_issue"),
            ).toTypedArray(),
        )
        .build()
}
```

`tools(...)` asks the server for its tools and gives them as tools of a run, for an agent or for `ai.generate`:

- **The model knows each one by the client and the tool**, like `github_search_issues`, so two servers can both have
  a `search`. What the providers do not take in a name becomes `_`, and a name past 128 characters is cut, with a
  hash of the whole. Two tools of a run with the same name fail the run before the model is called, with
  `DuplicateToolError`, whether they come from a server or not.
- **`only`, `readOnly` and `needsApproval` name the tools as the server does.** A name the server does not have
  fails, so that a typo does not quietly leave a tool without its approval. What the server says of a tool, like
  `readOnlyHint`, decides nothing: the spec says not to trust it unless the server is trusted, and that is for the
  application to decide. A call that needs approval pauses the run, as any [other](#approvals) does.
- **The schema is the server's, sent without strict mode**, which would ask the provider to rewrite a schema the
  application did not write.
- **The server is asked every time `tools(...)` is called.** Called once when the agent is built, the agent keeps
  those tools; called on every run, it sees the ones the server adds.

What the tool answers reaches the model as text, a line per piece. With no text but a JSON answer
(`structuredContent`), the model gets the JSON. A piece the model cannot get yet, like an image, becomes a line that
says there was one, so the answer does not look cut. A tool that ran and failed reaches the model as a `ToolError`
with what the server said, so it can fix the call; a call the server turned down is an `McpError`, which the model
reads as [any failure](#when-a-tool-fails).

### Calling a tool yourself

```kotlin
val github = mcp["github"]

val tools: List<McpToolDefinition> = github.listTools()
val result = github.callTool("search_issues", Json.obj("query" to "is:open label:bug"))
```

`listTools` follows the pages of the server to the last one. An `McpToolResult` has the `content` in order (`Text`,
or `Other` with its JSON for images, audio and resources), the `structuredContent`, and `isError` for a tool that
ran and failed. An `McpError` is a request that failed: its `code` is the one of JSON-RPC when the server gave one,
like -32602 for a tool it does not know, and its `status` is the HTTP status when it did not answer 200.

`McpClient.http(...)` and `McpClient.stdio(...)` build a client without the container.

### Old and new servers

The client finds out which revision a server speaks with its first request, and remembers it for its life:

- **Over HTTP**, the first request goes in 2026-07-28. A server of before turns it down with an error it does not
  know, and the client opens a session with the handshake of before (`initialize`, on 2025-11-25) and goes on in it.
  A server that lost the session, like one that restarted, turns the next request down with 404, or with 400 as the
  reference server does; the client opens a new session and sends the request once more. `close()` ends the
  session.
- **Over stdio**, the client asks with `server/discover`, which a server of 2026-07-28 answers. One of before answers
  with an error, or not at all in ten seconds, and gets the handshake of before.
- **Some arguments also go as headers** when a server of 2026-07-28 marks them with `x-mcp-header` in the schema, so
  that a gateway can route by them without reading the body. A tool whose marks break the rules of the spec is left
  out, with a warning that says why. A call the server turns down because the headers do not match the body, which
  means the tool changed since it was listed, lists the tools again and goes once more.

**The client declares no capabilities**, so a server does not ask it for input from the user (elicitation), for a
completion of a model (sampling) or for its roots. A server that needs one of them to answer fails the call with an
`McpError` that says what it asked for. Resources, prompts, OAuth and the notifications of a list that changed are
not there yet.

### Servers over stdio

The process starts with the first request, not when the client is built, and it is started as the official SDKs of
TypeScript and Python start it:

- **With only the safe part of the environment of the application**, like `PATH` and `HOME`, plus its `env`. A server
  of a third party, downloaded with `npx`, would get the keys of the application otherwise.
- **On Windows, a bare command is found on the path with its extension**: `npx` is `npx.cmd` there, which Java
  cannot start by its name alone. The same configuration works on every system.
- **Closing it closes its input, which asks it to end**; after two seconds it ends it and every process it started,
  since ending only `npx.cmd` would leave the `node` it started running. A shutdown hook closes the ones still
  running when the JVM stops, if the application did not; a JVM that is killed outright leaves them behind.

What the server writes to its errors goes to the log at debug, and its last twenty lines go in the error when the
process ends on its own. What was on the way then fails, and the next request starts it again.

### Timeouts and cancellation

The `callOptions` of the run reach every call of its MCP tools, as they reach the calls to the model:

- **A request waits for the shorter of the timeout of the run and the `requestTimeoutSeconds` of the server** (two
  minutes by default), so a server that hangs does not hold a long run. One that does not get its answer in time
  fails with an `McpError` that says so.
- **Cancelling the run stops the request** with a `CancelledError`, even while it waits for the answer, and the
  server is told, so it can stop what it does: over stdio and on a server of before, with `notifications/cancelled`;
  on a server of 2026-07-28 over HTTP, closing the connection is how it is told.

A server that cannot be reached is an `McpError` that names it.

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

## Telemetry

With [trantor-opentelemetry](trantor-opentelemetry.md) in the application, every generation and every run of the
agents is traced and measured, under the span that was current when it was called: the request that asked for
it, or the job. `addAI` passes the `OpenTelemetry` of the container to `AI` and to the `AgentRunner`, whether it
was registered before or after. Without an SDK behind it, it costs nothing.

It follows the OpenTelemetry semantic conventions for generative AI as they were at commit `e57c543` of
[semantic-conventions-genai](https://github.com/open-telemetry/semantic-conventions-genai) (2026-09-24). They are
all in Development and still change often, so a name here may change with them.

**The business data is not in the traces.** Usage per customer, cost and answers come from the result of the
run (`usage`, `estimatedCost`, `steps`), which is exact and complete. Traces are sampled and meant to see what
happened; the cost is not in them at all.

### What is traced

A generation is an agent without a name, which is how other libraries report theirs:

```
invoke_agent                            INTERNAL   the whole generation, with the usage of all its calls
├── chat gpt-4.1-mini                   CLIENT     one call to the model
│   └── POST api.openai.com             CLIENT     the http call of the provider (trantor-web-client)
├── execute_tool getWeather             INTERNAL   one tool
└── chat gpt-4.1-mini                   CLIENT
```

An agent alone is `invoke_agent {agent}`, and a team a workflow, named after the agent it starts with, with an
`invoke_agent` span for each stretch in which an agent had the conversation. A handoff ends one and starts the
next:

```
invoke_workflow support
├── run_guardrail onTopic               input guardrail
├── invoke_agent support
│   ├── chat gpt-4.1-mini
│   ├── run_guardrail allowed           tool guardrail, one for each call
│   ├── execute_tool researcher         an agent used as a tool
│   │   └── invoke_agent researcher     its own run, inside the tool that called it
│   │       ├── chat claude-haiku-4-5
│   │       └── …
│   └── execute_tool transfer_to_sales
└── invoke_agent sales
    └── chat claude-haiku-4-5
```

A team is a workflow even when nobody handed the conversation over, so the shape of the trace is the one of the
application and not of what the model decided. A run of an agent used as a tool is never a workflow, even with a
team of its own: it is a detail of the tool that runs it.

| Span | What it says |
|---|---|
| `chat {model}` | The provider, the model asked for and the one that answered, the id of the response, why it stopped, the settings that were set, the usage, the agent that made it, and `gen_ai.conversation.compacted` when it went with a [summary](#compacting-the-old-part) |
| `execute_tool {tool}` | The name, the id of the call, the description and the agent. A tool that fails marks its span alone: the model reads the failure and the run goes on |
| `invoke_agent {agent}` | The agent, its model and the usage of its stretch |
| `invoke_workflow {agent}` | The agent it starts with and the usage of the whole run |
| `run_guardrail {guardrail}` | Whether it was about the input, the output or a call, and what it decided |

A call that fails marks its `chat`, the agent and the run, with the class of the exception as `error.type`, and
the exception goes on as it was. The telemetry never fails what it watches: a span or a metric that cannot be
written is logged, and the run goes on.

**The usage of a span includes the agents it used as tools**, as `RunResult.usage` does: the run of the
researcher above counts in the stretch of support. Adding up the usage of every span of a trace counts it twice;
the one of the root span is the whole.

### Guardrails

The conventions have no span for a guardrail yet. `run_guardrail` follows the proposal in
[pull request 427](https://github.com/open-telemetry/semantic-conventions-genai/pull/427), not merged, with its
base attributes: what it looked at (`input` or `output`, and `llm` or `tool_call` with the id of the call), its
verdict (`allow`, `deny`, or `escalate` for one that asked for approval), what the run did (`allow` or `block`) and
the reason of a `Trip`, a `Reject` or an `AskForApproval`. The reason is written by the application and goes to the
traces, so it should not carry data of the user.

A trip is what a guardrail is for, so its span does not fail: the run it stopped does, with
`GuardrailTrippedError`. A guardrail that throws is what failed. A guardrail that calls a model has the spans of
that call inside its own.

### Approvals

A run that pauses for approval ends its spans well: waiting for a person is not a failure. What the trace shows:

- **A call a tool guardrail asked approval for** has its `run_guardrail` span, with `escalate` and the reason.
- **A call its tool asked approval for**, and one rejected when the run is picked up, have no span of their own,
  since they never ran. They are in the result of the run (`pending`, `resolved`) and in the conversation.
- **An approved call** runs in the run that picks the conversation up, which is a trace of its own, in an
  `execute_tool` span. The id of the call ties the two traces together wherever both have it: the `run_guardrail`
  of one and the `execute_tool` of the other, or what was said when it is [captured](#what-was-said).

**Not traced yet, until the conventions have it.** A decision about a call before it runs (`require_approval`,
`allow`, `deny`) is proposed as the event `gen_ai.tool.call.decision` in
[pull request 535](https://github.com/open-telemetry/semantic-conventions-genai/pull/535), and the pause and the
resumption of an agent as `gen_ai.agent.paused` and `gen_ai.agent.resumed` in
[pull request 445](https://github.com/open-telemetry/semantic-conventions-genai/pull/445). Neither is merged, and
the second needs an id of the run kept across the pause, which nobody has yet. Both are events, which OpenTelemetry
now writes as logs tied to the span and not as span events, whose API it
[deprecated in March 2026](https://opentelemetry.io/blog/2026/deprecating-span-events/); trantor-opentelemetry
exports no logs yet. They come with the conventions.

### Streams and tools at the same time

**A stream has the same spans**, and they hang from the span that was current when the stream was asked for,
wherever and whenever it is read. Nothing of the run is current between one event and the next, because that is
the code of whoever reads: its spans stay its own. A streamed `chat` says so, and how long its first chunk took.
A stream closed halfway ends the spans it left open without failing them; its `chat` says it never got a reason
to stop, which the conventions write as `error`.

**Tools that run at the same time** each get the context of the step on their own thread, so what a tool does
hangs from its span, and its logs carry the correlation id of the request.

### MCP

The requests to an [MCP](#mcp) server follow the conventions of OpenTelemetry for MCP (`docs/gen-ai/mcp.md` of
the same repository), in Development as well:

```
invoke_agent support
├── chat gpt-4.1-mini
└── execute_tool github_search_issues   the tool, with the attributes of its MCP request
    └── POST api.githubcopilot.com      the http call (trantor-web-client)
```

- **A tool an agent calls has no second span for its request**: the conventions ask not to repeat `execute_tool`,
  so the attributes of MCP go on it: `mcp.method.name`, `jsonrpc.request.id`, `mcp.protocol.version`,
  `mcp.session.id` on a server of before, `network.transport` (`tcp` or `pipe`), and `server.address` and
  `server.port` over HTTP.
- **Any other request is a client span** named after its method, and after its tool on `tools/call`: `tools/list`,
  `tools/call get_weather`, and the `initialize` or `server/discover` that find out what the server speaks. A request
  that fails has its JSON-RPC code as `error.type` and `rpc.response.status_code`, or the class of the exception when
  there is no code; a tool called directly that ran and failed has `tool_error`.
- **The context of the trace goes in the `_meta` of every request** (`traceparent`, `tracestate`, `baggage`), so a
  server that traces continues the same trace.

### What was said

The spans carry how each call went, not what was said. The conventions ask for that to be off by default:
messages carry the data of the users, and weigh much more than a span.

```json
{ "ai": { "telemetry": { "captureContent": true, "maxContentLength": 4000 } } }
```

| Setting | Default | What it is |
|---|---|---|
| `captureContent` | `false` (`OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT`) | Whether the spans carry what was said |
| `maxContentLength` | none | The longest a text may be, in characters. A longer one is cut and marked with `…`, and the JSON around it stays whole |

`AITelemetrySettings`, from the `ai.telemetry` section, and in code with
`services.configure<AITelemetrySettings> { settings, _ -> ... }`. With it on:

| Span | Attribute | What |
|---|---|---|
| `chat` | `gen_ai.system_instructions` | The instructions, and the dynamic ones last |
| `chat` | `gen_ai.input.messages` | The conversation as it went to the model, with the name of the agent on its turns |
| `chat` | `gen_ai.output.messages` | What the model answered |
| `chat` | `gen_ai.tool.definitions` | The tools it could call, without the JSON Schema of their args |
| `execute_tool` | `gen_ai.tool.call.arguments` | The args the tool ran with, after the hooks |
| `execute_tool` | `gen_ai.tool.call.result` | What the model reads of it |

They are JSON strings in the shape of the conventions. Reasoning goes only when the model let it be read; what
it signed or encrypted never goes, nor the raw content of a part only one provider knows.

Where the content has to be cleaned before it is kept, a Collector can do it on the way, with its
[transform processor](https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/processor/transformprocessor).

### Metrics

Measured at the same points, with the units and the buckets of the conventions:

| Metric | What |
|---|---|
| `gen_ai.client.operation.duration` | How long each call to the model took, with `error.type` when it failed |
| `gen_ai.client.operation.time_to_first_chunk`, `time_per_output_chunk` | For streamed calls only |
| `gen_ai.client.inference.usage.input_tokens`, `output_tokens`, `cache_read.input_tokens`, `cache_write.input_tokens`, `reasoning.output_tokens` | Counters of what was spent, for totals and rates |
| `gen_ai.client.inference.operation.input_tokens`, `output_tokens` | Histograms of each call, for percentiles |
| `gen_ai.execute_tool.duration` | How long each tool took |
| `gen_ai.invoke_agent.duration`, `inference_calls`, `tool_calls` | For each generation, agent and stretch: how long it took, and the calls it made itself |
| `gen_ai.invoke_workflow.duration` | How long each run of a team took |

The token counters say `gen_ai.token.modality = unknown`: Trantor cannot tell text tokens from image or audio
ones, and that is what the conventions ask for then. The calls of an agent are counted in the agent that made
them, not in the one that used it as a tool, so each call counts once. A stream closed halfway is not measured,
since it did not finish.

### Without the facade

`DefaultAI`, `AgentRunner` and `ToolLoop` take an `openTelemetry` and `telemetrySettings`, and trace nothing
without them:

```kotlin
val loop = ToolLoop(model, tools, openTelemetry = openTelemetry, telemetrySettings = AITelemetrySettings())
```

---

## Anthropic

```kotlin
services.addAnthropic { anthropic, _ -> anthropic.cache = AnthropicCache(system = true, conversation = true) }
```

The key comes from `ANTHROPIC_API_KEY`, and `AnthropicConfig` also holds `baseUrl`, the pinned
`anthropic-version`, a list of `betas` sent as `anthropic-beta`, `defaultMaxTokens` and `readTimeout`.

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

**When the past does change.** Sometimes a call goes out with something before a thinking block that is
not what it was produced after: the application changed the instructions of an agent between two turns, a
generation goes on with other tools, one agent handed the conversation over to another in a history whose
answers are not signed by their agent, or a context policy sent less than the whole conversation — it took
the oldest messages out, or shortened old tool results, which Anthropic counts as an edit too. That
thinking would be refused, and there is no way around losing it: the model goes on without that
reasoning, as it would with another model. What the adapter makes sure is that it loses nothing else.

A handoff between agents does not get here as long as the answers are signed: the new agent reads the turns
of the one before as context, with its name and without its thinking, so the only thinking
a call of it carries is its own.

Each thinking block also remembers a fingerprint of what came before it, in the same metadata: the system
prompt, the tools and the messages, without the cache marks, which can move, and without the thinking,
which can be left out from the start. A call leaves out every thinking block up to the last one whose
fingerprint is not what comes before it now, with a warning, and sends the rest. It is not only the blocks that changed:
Anthropic takes thinking left out from the start of the conversation, or from its end, but not from its
middle, so a block that still fits goes too when one after it did not. The blocks after the last one
that changed — the reasoning produced since — stay valid, and stay. A conversation that goes on, in the
next turn or the one after, keeps leaving out the same old blocks and keeps all the new ones.

**What the other libraries do.** Most of them let the block go instead. The option for that is
`thinking.block_binding.prefix_mismatch_behavior`: with `"drop_block"`, Anthropic removes a block whose
past changed, and every thinking block after it, before the model reads the call; the call goes through,
but the model no longer sees what it reasoned there. Zed sends it on every call to those models;
PydanticAI sends nothing, retries once with it when the 400 comes, and warns; Goose makes it a setting.
It is not used here: after a change of system prompt or tools, or a cut of a context policy, "every
thinking block after it" is all the reasoning produced since, on every call of the conversation from then
on.

---

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
