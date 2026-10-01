# Telemetry

With [trantor-opentelemetry](../trantor-opentelemetry.md) in the application, every generation and every run of the
agents is traced and measured, under the span that was current when it was called: the request that asked for
it, or the job. `addAI` passes the `OpenTelemetry` of the container to `AI` and to the `AgentRunner`, whether it
was registered before or after. Without an SDK behind it, it costs nothing.

It follows the OpenTelemetry semantic conventions for generative AI as they were at commit `e57c543` of
[semantic-conventions-genai](https://github.com/open-telemetry/semantic-conventions-genai) (2026-09-24). They are
all in Development and still change often, so a name here may change with them.

**The business data is not in the traces.** Usage per customer, cost and answers come from the result of the
run (`usage`, `estimatedCost`, `steps`), which is exact and complete. Traces are sampled and meant to see what
happened; the cost is not in them at all.

## What is traced

A generation is an agent without a name:

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
| `chat {model}` | The provider, the model asked for and the one that answered, the id of the response, why it stopped, the settings that were set, the usage, the agent that made it, and `gen_ai.conversation.compacted` when it went with a [summary](runs.md#compacting-the-old-part) |
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

## Guardrails

The conventions have no span for a guardrail yet. `run_guardrail` follows the proposal in
[pull request 427](https://github.com/open-telemetry/semantic-conventions-genai/pull/427), not merged, with its
base attributes: what it looked at (`input` or `output`, and `llm` or `tool_call` with the id of the call), its
verdict (`allow`, `deny`, or `escalate` for one that asked for approval), what the run did (`allow` or `block`) and
the reason of a `Trip`, a `Reject` or an `AskForApproval`. The reason is written by the application and goes to the
traces, so it should not carry data of the user.

A trip is what a guardrail is for, so its span does not fail: the run it stopped does, with
`GuardrailTrippedError`. A guardrail that throws is what failed. A guardrail that calls a model has the spans of
that call inside its own.

## Approvals

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

## Streams and tools at the same time

**A stream has the same spans**, and they hang from the span that was current when the stream was asked for,
wherever and whenever it is read. Nothing of the run is current between one event and the next, because that is
the code of whoever reads: its spans stay its own. A streamed `chat` says so, and how long its first chunk took.
A stream closed halfway ends the spans it left open without failing them; its `chat` says it never got a reason
to stop, which the conventions write as `error`.

**Tools that run at the same time** each get the context of the step on their own thread, so what a tool does
hangs from its span, and its logs carry the correlation id of the request.

## MCP

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

## What was said

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

Where the content has to be cleaned before it is kept, a Collector can do it on the way, with its [transform
processor](https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/processor/transformprocessor).

## Metrics

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

## Without the facade

`DefaultAI`, `AgentRunner` and `ToolLoop` take an `openTelemetry` and `telemetrySettings`, and trace nothing
without them:

```kotlin
val loop = ToolLoop(model, tools, openTelemetry = openTelemetry, telemetrySettings = AITelemetrySettings())
```
