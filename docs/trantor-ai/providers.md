# Providers

An adapter implements `AIProvider` and adds itself to the `ModelRegistry` through `configure<ModelRegistry>`,
so an application that never asks for it never pays for it.

Provider specifics that do not fit the contract travel in `ChatRequest.providerOptions` and come back in
`providerMetadata`. Each adapter reads only its own and warns about the rest. Options are merged into the
request body recursively: adding a field next to one the
adapter already set works, and a real conflict is an `InvalidProviderOptionError` naming the full path.

Currently **OpenAI** on the Responses API and **Anthropic** on the Messages API.

## OpenAI

```kotlin
services.addOpenAI { openAI, _ -> openAI.store = false }
```

The key comes from `OPENAI_API_KEY`, and `OpenAIConfig`, from `ai.providers.openai`, also holds `baseUrl`,
`organization`, `project`, `store` and `readTimeout`. `store` is whether OpenAI keeps the response on its side, where
its dashboard shows it for 30 days; left out, it is OpenAI's own default. Trantor sends the whole conversation on
every call, so storing buys nothing beyond looking a call up later.

The adapter talks to the Responses API. A model that reasons is asked for its reasoning encrypted, which goes back on
the next call so the model keeps its own chain. `stopSequences` and `seed` have no field there, and are dropped with
a warning. OpenAI caches the start of a request on its own, with nothing to mark.

**`OpenAIOptions`** has what only OpenAI has: `serviceTier` (`Flex` is slower and cheaper, `Priority` faster and
dearer), `store` for one call, `promptCacheKey` to keep calls that share a prefix on the same cache,
`safetyIdentifier` for its abuse detection, `truncation` and `verbosity`. What is not there yet goes as
`RawOptions("openai", ...)`.

A tool of the provider is named `openai.` and its type, with the rest of what it takes as args:
`ProviderToolSpec("openai.web_search", Json.obj())`.

## Anthropic

```kotlin
services.addAnthropic { anthropic, _ -> anthropic.cache = AnthropicCache(system = true, conversation = true) }
```

The key comes from `ANTHROPIC_API_KEY`, and `AnthropicConfig` also holds `baseUrl`, the pinned
`anthropic-version`, a list of `betas` sent as `anthropic-beta`, `defaultMaxTokens` and `readTimeout`.

**The system prompt** is a field, so the first one goes there wherever it was written. The rest stay
where they were, as `role: "system"` messages, on the models that take one in the middle of the
conversation — Opus 4.8, 5 and 5.5, Sonnet 5.5, Fable and Mythos 5 and 5.1 — which keeps the cached prefix intact. On
the others they are joined into the field, with a warning. The catalog says which is which, so there is
nothing to configure. The `dynamicSystem` of a request goes by the same rule: last, as a system message,
where the model takes it, and as a second block under the system prompt where it does not (see [What
changes goes last](models.md#what-changes-goes-last)).

**Prompt caching is three independent flags.** Unlike OpenAI, which caches on its own, Anthropic caches
only up to a mark, and a later call reads the cache only if everything up to that mark is exactly the
same. Each flag puts a mark in a different place, and they add up:

| flag | where the mark goes | pays off when |
|---|---|---|
| `system` | at the end of the system prompt, before the `dynamicSystem` when it goes under it | the same long system prompt comes before a new question every time |
| `tools` | on the last tool that is not deferred | the tools stay the same and the system prompt does not |
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
and none reads it back. With `system`, the second call reads the whole prompt back and pays the plain price
only for the question. That is why `system` exists, and why the two go together for an agent.

Everything is off by default: a write costs 1.25 times the input for five minutes and twice for an hour,
and only pays off when it is read back. Below the model's minimum, between 512 and 4,096 tokens depending
on the model, nothing is cached and nothing fails either. Anthropic takes four marks per request and
these use at most three; a cut somewhere else goes in the `ProviderMetadata` of the part it follows.

**Strict mode has a budget per call.** Anthropic takes at most 20 strict tools and 16 parameters that can be null
(`anyOf` or a list of types, at any depth) in one call, adding up every strict schema — the answer in JSON and the
deferred tools of its search included — and answers 400 to the whole call past that. The adapter holds as many tools
to their schema as fit, in the order they go: the answer first, since it has nothing to fall back to, then the tools
up front, then the deferred ones. The rest go without strict mode and a warning names them; their args are still
read and checked, and a call that does not fit goes back to the model. Its third limit, 24 optional parameters,
never adds up, since a closed schema requires every property. A grammar can still be too complex in ways with no
number to check beforehand, and that one fails the call.

**A tool of the provider is named by the versioned type** Anthropic gave it, with its own name as an
argument, since a server tool carries both:

```kotlin
ProviderToolSpec("anthropic.web_search_20260209", Json.obj("name" to "web_search"))
```

**`AnthropicOptions`** is the way past the catalog. It is sent as it was written, without asking what the
model takes, so whoever knows their model gets exactly what they asked for: `effort` reaches the `xhigh`
and `max` levels `Reasoning` does not have, `thinking` names a budget, the adaptive mode or `BetweenTools`, and
there are `cache`, `userId` and `serviceTier`.

### Thinking tied to the conversation

On Claude Opus 5.5, Sonnet 5.5 and Fable 5.1, a thinking block that goes back to Anthropic has to come after exactly
what it came after when it was written: nothing already sent can be edited, moved or removed, or the call answers
400. Anthropic does it by default on accounts created from 31 August 2026 on, and on older ones only if a call asks
for it. The adapter takes care of it, and asks one thing of the application, which the signature of the thinking
already asks: that the parts of a message are stored with their metadata, as they came.

- **The `dynamicSystem`** goes as a system message that lasts one turn, so that changing it does not edit the past.
- **When the past did change** — the instructions of an agent changed between two turns, a generation goes on with
  other tools, a context policy cut, or the answers of a team are not signed by their agent — the thinking written
  before the change is left out, with a warning, and the model goes on without that reasoning. Nothing else is lost.

How it does it is in [the notes for maintainers](internals.md#thinking-tied-to-the-conversation).
