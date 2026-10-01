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

`AI` and the `AgentRunner` read and describe the args of the tools, the objects the models answer and the output of
the agents with the `JsonSerializer` of the container, the one `addGsonSerializer` registers with the types of the
application, or a `GsonSerializer` of its own when there is none.

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
see [Telemetry](trantor-ai/telemetry.md).

The MCP servers whose tools the application uses are registered apart, with `addMcp()`: see [MCP](trantor-ai/mcp.md).

---

## The guides

| Guide | What it covers |
|---|---|
| [Runs](trantor-ai/runs.md) | `AI`: a generation and its tool loop, the history, compaction, objects, streaming, `ToolLoop` |
| [Tools](trantor-ai/tools.md) | Writing a tool, tool search, failures, calls at the same time, approvals, tools at the model layer |
| [Agents](trantor-ai/agents.md) | Agents, one using another, handoffs, hooks, guardrails, the history and approvals of a team |
| [MCP](trantor-ai/mcp.md) | The tools of MCP servers in a run, and calling them directly |
| [Models](trantor-ai/models.md) | One call to a model: its shapes, structured output, streaming, reasoning, middlewares, cancellation |
| [The model catalog and cost](trantor-ai/catalog.md) | What each model takes, describing a new one, what a call probably cost |
| [Providers](trantor-ai/providers.md) | OpenAI and Anthropic: their settings, their options, caching |
| [Telemetry](trantor-ai/telemetry.md) | The traces and metrics of runs and agents |
| [Notes for maintainers](trantor-ai/internals.md) | Why the catalog is as it is, and what the adapters do on the wire |
