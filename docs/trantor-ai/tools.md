# Tools

How a run calls the code of the application: writing a tool, letting the model search a large catalog,
what the model reads when one fails, which calls run at the same time, and the ones that wait for a person.

## Writing one

```kotlin
class SearchProductsTool(private val catalog: Catalog): Tool<SearchProductsTool.Args>() {
    override val name = "searchProducts"
    override val description = "Products of the store whose name contains the text"
    override val readOnly = true

    override fun execute(args: Args, context: ToolContext) = context.json(catalog.search(args.text))

    data class Args(@Description("Part of the name of the product") val text: String)
}
```

A tool plays the part of a controller: it turns what the model asked for into an operation of the
application, and its result into something the model can read.

- **The args are a class of the application**, which the serializer of the application reads and describes: the
  `JsonSerializer` of the container, the `GsonSerializer` of [trantor-gson](../trantor-gson.md) unless it registered
  another. The schema the model sees is the one it reads by, so the two cannot drift apart, and the args can be of
  the types of the domain: an id, `Money`, a value object the application registered, a hierarchy, `Maybe`.
  `@Description` tells the model what the name and the type do not, and the validations of Jakarta go into the
  schema. A type the serializer cannot describe fails the first run that uses the tool, naming the field.
- **They are read leniently**: a field the args do not have is ignored, a nullable arg the model left out reads as
  null, and a null for an arg that cannot be null reads as its default. An optional arg is best nullable, since
  OpenAI in strict mode sends every field and Anthropic leaves out the ones it has nothing for. What does not fit
  goes back to the model, which fixes the call.
- **What it answers**: `context.json(value)` writes an object as the application writes JSON, with the types it
  registered; `ToolResult.json` takes JSON the tool already has, and `ToolResult.text` text.
- **It is strict**, holding the model to the schema, unless the args have a `Maybe` of what can be null: strict mode
  sends every field, so the model could never leave that one as it is. The tool goes without it, and the log says
  so once. A `Maybe` of what cannot be null is fine: its `null` is "leave it as it is".
- **The type of the args** is the one `Tool<Args>` says. A tool that does not know it, like one generic in its
  args, is given it: `Tool<T>(typeOf<Args>())`.

`ToolContext` carries the `callId`, the `toolName` and the `RunContext` of the run, a typed bag that
whoever launches the run fills: `context.run.require<Tenant>()`. What a tool needs from the application —
a repository, the executor — it gets by constructor, like any other service.

A tool that calls a model itself takes `context.callOptions`, the timeout, the cancellation and the headers
of the run, so that cancelling the run stops that call too; and it answers `ToolResult.text(...).withRun(run)`
with the run it made, which stays in the step (`step.toolRuns`) and whose usage and cost count as the run's.
Otherwise the run spends without anyone seeing it. An agent that runs as a tool does both on its own.

Tools the provider runs on its side, like a web search, are not run again: their results came in the
answer.

## Tool search

```kotlin
val result = ai.generate {
    system("Sos el soporte de la tienda. Podés buscar tools de envíos, pagos, pedidos y devoluciones.")
    user(question)
    tools(orders)
    searchableTools(*storeTools.toTypedArray(), *mcp["github"].tools().toTypedArray())
}

val support = Agent("support").tools(orders).searchableTools(*storeTools.toTypedArray()).build()
```

A catalog of tens of tools costs the tokens of all of them on every call, and a model picks worse among many.
`searchableTools(...)` gives tools the model **searches for** instead of being told about them up front; `tools(...)`
stays for the ones it always needs. Whether a tool is searched depends on the run, not on the tool, so any tool can
be one, those of an MCP server included.

- **The model searches with `search_tools(query)`**, a tool of Trantor that looks for the words of the query in the
  name, the description and the args of the searchable tools, and answers up to five with their name and description.
  A search that finds nothing answers the names of all of them (up to a hundred), so the model can search again with
  their words: a model asked in Spanish searches Spanish words, and the tools may be named in English.
- **Where the provider can load tools, it loads what the search found.** Claude from Haiku, Sonnet and Opus 4.5 on
  (Sonnet 5 is not on Anthropic's list) and GPT from 5.4 on: the searchable tools go deferred, the model sees none of
  them until a search finds them, and the provider loads those — from references to them on Anthropic, from their
  definitions on OpenAI — without the cached prefix changing. The catalog says which model can
  (`ModelFeatures.DeferredTools`), and a model it does not know is taken not to, since one that cannot answers 400.
- **Elsewhere, the loop tells the model about what was found** from the next step on, like any other tool, which
  breaks the cache from there.
- **What was found lives in the conversation**: the results of `search_tools` stay in the history like any other
  part, so a `Session` keeps them and a later turn has those tools without searching again. After a
  [compaction](runs.md#compacting-the-old-part) the model searches again.
- **A searchable tool is still a tool of the run**: one with the name of another fails the run with
  `DuplicateToolError` before the model is called, even if it would never be found.

**Another search** is given with `toolSearcher(...)`, in `ai.generate` and in an agent: a `ToolSearcher` takes the
query and the specs of the searchable tools and answers the ones that fit, best first — with embeddings, a search
engine of the application, anything. What it finds is loaded by the provider, or told by the loop, as above.

```kotlin
class EmbeddingToolSearcher(private val embeddings: ToolEmbeddings): ToolSearcher {
    override fun search(query: String, tools: List<FunctionToolSpec>) = embeddings.nearest(query, tools, limit = 5)
}

val support = Agent("support")
    .searchableTools(*storeTools.toTypedArray())
    .toolSearcher(EmbeddingToolSearcher(toolEmbeddings))
    .build()
```

**What it saves.** A catalog of 47 small tools, one question, the input tokens of the whole run:

| | all up front | searchable |
|---|---|---|
| Claude Sonnet 4.5 | 9,939 | 2,445 |
| Claude Sonnet 5.5 | 11,952 | 4,645 |
| GPT-5.4 | 4,335 | 1,310 |
| GPT-4.1 mini, which cannot load tools | 4,316 | 1,450 |

A search costs a step: what it found goes back as the result of a tool before the model can call it.

**The search is only as good as the words.** The model searches with the words of the question. A searchable tool
wants a name and a description with the words a user would say, and the instructions should say what kinds of tools
there are to search, as Anthropic recommends.

## When a tool fails

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

## Calls at the same time

When the model asks for several calls in one step and **every** tool of the step is `readOnly`, they run
at the same time, each on its own virtual thread. Otherwise they run one after the other, in the order the
model asked for them, since two calls that write could depend on each other.

- `readOnly` is `false` by default: a tool says it only reads, and nobody checks it.
- The results go back in the order of the calls, not in the order they finished.
- They all finish before the run goes on, even when one fails, and each failure is handled as above.
- A cancellation interrupts every one of them.
- A tool that is `readOnly` can run on two threads at once, so it cannot keep mutable state of its own.

## Approvals

A tool can ask for a person to approve a call before it runs:

```kotlin
class RefundTool(private val payments: Payments): Tool<RefundTool.Args>() {
    override val name = "refund"
    override val description = "Gives back the money of an order, in dollars"

    override fun needsApproval(args: Args, context: ToolContext) = args.amount > 100

    override fun execute(args: Args, context: ToolContext) = ToolResult.text(payments.refund(args.order, args.amount))

    data class Args(val order: Int, val amount: Int)
}
```

`needsApproval` gets the args as the model sent them, before any hook changes them, which are the ones the person
sees. Args that do not fit the tool are not asked about: the call goes back to the model as an error, as always.
A [tool guardrail](agents.md#guardrails) can ask for approval too, for rules that depend on who the run is for.

**A call that waits does not run, and the run ends there.** The other calls of the step run, the model is not
called again, and the result says so:

```kotlin
val result = ai.generate { session(session); user(question); tools(orders, refund) }

if (result.paused) {
    result.pending          // each call that waits: the call (its id, tool and args), the agent, the reason
    notifyWhoApproves(result.pending)
}
```

The session keeps the paused run like any other: the answer of the model with all its calls, the results of the ones
that ran, and nothing for the ones that wait. That is where the pause lives: **the call without its result is the
mark**, and there is no other store. A paused run is not [compacted](runs.md#compacting-the-old-part), since it is not
over, and its `text` is whatever the model said before asking.

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
  reads that the call was not approved, that it should not call it again and that it should tell the user: a model
  told only that it was not approved may ask for it again right away. A message of the application runs the same
  risk.
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
  of a call is not a permission. Without a session, a client that sends the history can approve a call it made up.
  A tool that does something sensitive still authorizes it.
- **One run at a time on a conversation**, as always: two decisions at once would both find the call waiting.
- **In a job**, a session per job, and the decision is another job that picks it up.
- **The last step allowed**: if every call of it waits, the run fails with `MaxStepsExceededError`, as it would
  with calls that could not run.
- **Not yet:** changing the args when approving, approving a tool for the rest of the conversation, and a call
  answered by someone else, like the front end.

The pause lives in the conversation and not in a run state stored apart: there is one thing kept instead of two
that have to agree, and nothing serialized that a deploy could break while a run waits. What that gives up is a
single run across the pause, whose usage and steps are two results here. What the traces show of it is in
[Telemetry](telemetry.md#approvals).

## Tools at the model layer

```kotlin
val weather = FunctionToolSpec(
    name = "get_weather",
    description = "The weather in a city",
    parameters = serializer.schemaOf<WeatherQuery>(),
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
results it is given. Running them is the job of the tool loop, in [Runs](runs.md).

**Who chooses** is `toolChoice`: `Auto` — the model decides, and the default — `Required` for any of
them, `Named` for one by name, and `None`. `ChatSettings.parallelToolCalls = false` asks for one call at
a time; which field carries that is the adapter's business, and on Anthropic it is not a field of the
request but part of the choice.

**A tool is strict by default**, which holds the model to the schema instead of hoping, and the schema is
closed exactly the way structured output closes it. Where the model has no grammar for it the tool still
goes, without the guarantee and with a warning: half a tool beats no tool.

**`deferLoading = true`** tells the provider about a tool the model does not see until the search of the request
finds it: the tool with **`searchesTools = true`**, which the adapter sends as a search the client runs, and whose
answer — the JSON of `search_tools`, `{"tools": [{"name": ...}]}` — it turns into what the provider loads the tools
from. On a model that does not load deferred tools (`ChatModel.loadsDeferredTools`), or with no search in the
request, the tool goes up front, with a warning. The tool loop sets both for the [searchable tools](#tool-search).

**Being told to call one is not something every model takes.** Claude Opus 5.5, Sonnet 5.5, Fable 5.1 and Mythos
5.1 answer 400 to a forced call, and any Claude refuses it while it is thinking to a budget — the second capability that
is not a property of the model but of two settings meeting. Either way the choice goes back to `auto`,
which is the default anyway, and the warning says which of the two it was.
