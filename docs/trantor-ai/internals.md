# trantor-ai: notes for whoever maintains it

What the guides leave out because an application does not need it: why the model catalog is shaped as it is, how it
is kept, and what the adapters do on the wire.

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

### Data for what the model is, code for how settings meet

Some capabilities are not a property of the model but of two settings meeting. The GPT-5.x families
refuse the sampling settings **while they are reasoning**, and take them again once they are told not to
reason at all; GPT-6 Astra and GPT-6.1 Sol cannot be told that, so for them the refusal is flat. GPT-6 Sol and
Luna can be told not to reason, but nothing says they take a `temperature` then, so they are kept without one.

Being told not to reason is itself something a model can refuse: GPT-6 Astra and GPT-6.1 Sol answer 400 to an
effort of `none`, and Claude Opus 5.5, Sonnet 5.5, Fable and Mythos always think and answer 400 to thinking
disabled. There `Reasoning.Off` is not sent, and the warning says that a lower effort is how they think less. Sonnet
5.5 has a lowest setting instead, `between_tools`: no thinking before it answers, only short progress notes between
tool calls. `Reasoning.Off` becomes that there, except at an effort of `xhigh` or `max`, which Anthropic refuses it
at, and the warning says so.

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

## Anthropic on the wire

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

**Tool results are a turn of the user**, because a result is something the model is told. Anthropic
matches one to its call by id alone, so the name of the tool has nowhere to go. And it refuses a user
message that has anything before its results — *"tool_use ids were found without tool_result blocks
immediately after"* — so the adapter moves them to the front: that is a rule about the wire, and asking
the next question right after the answer is a reasonable way to build a conversation.

**A stream ends differently.** OpenAI sends the whole response as a last event. Anthropic sends nothing of the kind:
a message opens empty, its blocks arrive one by one and the last events say how it ended, so its adapter puts the
message back together as it arrives and runs it through the same mapper a plain call uses.

### Thinking tied to the conversation

This is the strangest rule of the whole API, and nothing an application has to do about it: the adapter
does it. It is written down because it shapes everything that touches a conversation, and because it
fails in a way that is hard to trace back.

**What Anthropic does.** On Claude Opus 5.5, Sonnet 5.5 and Fable 5.1, each thinking block the model returns is tied
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

**Why not `drop_block`.** `thinking.block_binding.prefix_mismatch_behavior` set to `"drop_block"` has Anthropic remove a
block whose past changed, and every thinking block after it, before the model reads the call; the call goes through, but
the model no longer sees what it reasoned there. After a change of system prompt or tools, or a cut of a context policy,
"every thinking block after it" is all the reasoning produced since, on every call of the conversation from then on.
