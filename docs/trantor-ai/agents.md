# Agents

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
  for what changes from one call to the next (see [What changes goes last](models.md#what-changes-goes-last)). Each is a
  string or a function of the `RunContext`, asked on every step, so what a tool changed is read on the next one.
- `model` is a reference or an alias, `default` when left out, or a `ChatModel` in hand. `settings { }` and
  `options(...)` go on every call the agent makes; an option for another provider is left out with a warning.
- `with(value)` carries a value of the application, like the id of an assistant, which a hook or a tool gets back
  by its type: `context.agent.require<AssistantId>()`. One value per type, so a `String` is best given a type of
  its own.

## Running one

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
| `decisions(...)` | What a person decided about the calls a paused run left waiting: [Approvals](tools.md#approvals) and [in a team](#approvals-in-a-team) |

An `AgentRunResult` has what a `RunResult` has — `text`, `newMessages`, `usage`, `estimatedCost`, `warnings`,
`toolFailures`, `paused`, `pending` — plus the agent of each step in `steps`, the one that answered in
`lastAgent`, and a `runId` that its tools and hooks see too. `result.result` is the `RunResult` underneath.

## Answering an object

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

## One agent using another

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
written by the model that calls, so its instructions say what the specialist is for and what not to make up: a
model that writes a task tends to add details nobody gave it.

## Handing the conversation over

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

Its reasoning does not go, and what is kept does not change. Sent as they were, the turns of the one before would read
as the new agent's own, calls to tools it does not have included, and it gets confused. So the application keeps the
agent of each message along with it; an answer without one goes as it is.

Since nothing of the other agent's provider travels, support can run on OpenAI and sales on Anthropic. A small
model may still ignore what it was told and work out again what the first agent found. When that matters, the
agent that answers is better off asking for it as a tool.

## Several at once

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

## Hooks

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

## Guardrails

A guardrail is a check that can stop a run. There is one kind for each thing a run can go wrong on:

- **`InputGuardrail`**, on the conversation, before the first call to the model: a request it should not take
  costs no call and runs no tool.
- **`OutputGuardrail`**, on the final answer, once the run ended well. Only the final answer: the text of an agent
  before it calls a tool, or of one that handed over, is not checked.
- **`ToolGuardrail`**, on each call the model asks for, with its args as the model sent them. Every call of a step
  is checked before any of them runs, so a trip leaves no step half done. Besides passing or stopping the run, it
  can `Reject(message)` a single call: the call does not run, the model reads the message as its error, and the
  run goes on with a warning. Or it can `AskForApproval(reason)`: the call waits for a person, as when its tool
  [asks](tools.md#approvals), with the reason in `pending`.

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

## The history of a conversation with agents

A run takes a `session(...)` and a `contextPolicy(...)`, which work as they do for a generation ([The
history](runs.md#the-history-where-it-is-kept-and-what-each-call-sends)): the conversation is what the session holds and
then the messages the run got, and the policy decides what each call sends of it. A few things are particular to
agents:

- The session keeps what the run added only once it ended well: after the output guardrails and `afterRun`, so a
  run that fails or trips keeps nothing.
- The instructions of an agent are not messages of the conversation, so they are never kept.
- The session keeps each answer with the agent that wrote it, which is what the next run needs to tell the turns
  of the others.
- A `compaction(...)` runs after the output guardrails, and `afterRun` sees the result with the conversation
  compacted.
- A run that [paused](tools.md#approvals) is kept too, and heard of by `afterRun`, but not checked by the output
  guardrails nor compacted: it has no final answer yet.

## Approvals in a team

A paused run of agents works as [one of a generation](tools.md#approvals), with a few things of its own:

- **It picks up with the agent that made the calls.** After support handed the conversation over to sales and sales
  asked for a refund, `agents.run(support) { team(sales); decisions(...) }` goes on as sales, with its tools and its
  hooks: the answer that made the calls says who made them. That agent has to be the one of the run or in its team,
  or the run fails before anything runs.
- **A handoff in the step that paused is not made**: the model reads that it was not, since the run that picks the
  conversation up could not tell, and can hand it over again then. Every other call of the step runs.
- **An approved handoff hands the conversation over** before the first step, and a stream says `Handoff`.
- **`output()` of a paused run** fails with `NoObjectGeneratedError`, saying it is waiting for approval.
- **An agent used as a tool** can be approved before it runs, with a tool guardrail on the name of its tool. A call
  that waits inside its own run is not supported: the run that called it cannot pause in its place, so it fails with
  `NestedApprovalError`.
- **In a stream with the text held back** by the output guardrails, the text of the step that paused comes out
  before its `ApprovalRequested`.

## Streaming a run of agents

```kotlin
agents.stream(support, Message.user(question)) { team(sales) }.use { stream ->
    stream.textDeltas().forEach { print(it) }

    val result = stream.result()
}
```

The events are those of a generation, with a `Handoff(from, to)` between the last step of an agent and the first
of the next. When a guardrail stops the run, its last event is `GuardrailTripped`, and reading on throws the
`GuardrailTrippedError`.

