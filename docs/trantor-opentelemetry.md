# trantor-opentelemetry

Traces of what an application does — the requests it serves, the calls it makes, the jobs it runs — and its
metrics, sent over OTLP to whatever backend reads them: Jaeger, Grafana Tempo, Prometheus, Honeycomb, Datadog, an
OpenTelemetry Collector.

Trantor is split the way OpenTelemetry itself is:

- **The API** (`opentelemetry-api`) is in `trantor-primitives`, like `slf4j-api`. Every module traces with it,
  and without an SDK it does nothing and costs nothing.
- **The SDK and the exporter** are in this module, `trantor-opentelemetry`. An application adds it when it
  wants its traces to go somewhere.

---

## Sending traces

```kotlin
services.addOpenTelemetry()
```

That registers an `OpenTelemetry` in the container, built the first time something asks for it. The server,
the HTTP client and the jobs take it from there, whether `addOpenTelemetry()` was called before or after them.

```json
{
  "openTelemetry": {
    "endpoint": "http://localhost:4318",
    "headers": { "x-honeycomb-team": "..." }
  }
}
```

`OpenTelemetrySettings`, from the `openTelemetry` section. Every default comes from the standard `OTEL_*`
variable when it is set, so a deployment configured for OpenTelemetry keeps working; the configuration wins
over the variable.

| Setting | Default | What it is |
|---|---|---|
| `enabled` | `true` (`OTEL_SDK_DISABLED`) | `false` registers the no-op `OpenTelemetry`: spans are opened and go nowhere |
| `serviceName` | the `appName` of the host (`OTEL_SERVICE_NAME`) | The `service.name` of every span |
| `endpoint` | a collector on this machine (`OTEL_EXPORTER_OTLP_ENDPOINT`) | The base URL: over HTTP the spans go to `v1/traces` under it, and the metrics to `v1/metrics` |
| `protocol` | `http/protobuf` (`OTEL_EXPORTER_OTLP_PROTOCOL`) | Or `grpc` |
| `headers` | none (`OTEL_EXPORTER_OTLP_HEADERS`) | Sent with every export: where most backends take their key |
| `resourceAttributes` | none (`OTEL_RESOURCE_ATTRIBUTES`) | Added next to `service.name` and `deployment.environment.name` |
| `samplingRatio` | `1.0` | The fraction of the traces that start here to keep |
| `metricExportInterval` | `60000` (`OTEL_METRIC_EXPORT_INTERVAL`) | How often the metrics leave, in milliseconds |
| `registerGlobal` | `true` | Also make it the `GlobalOpenTelemetry`, for libraries that look there |

To change them in code:

```kotlin
services.addOpenTelemetry { settings, _ -> settings.samplingRatio = 0.1 }
```

To try it locally, Jaeger takes OTLP over both protocols and shows the traces at `http://localhost:16686`:

```bash
docker run -d --name jaeger -p 16686:16686 -p 4317:4317 -p 4318:4318 jaegertracing/jaeger:latest
```

Jaeger takes no metrics. `grafana/otel-lgtm` takes both, and shows them in Grafana at `http://localhost:3000`:

```bash
docker run -d --name lgtm -p 3000:3000 -p 4317:4317 -p 4318:4318 grafana/otel-lgtm:latest
```

**Sampling** keeps the decision of the caller: a trace that arrives sampled from another service stays sampled
here, whatever the ratio, so a trace is never cut in half. `OTEL_TRACES_SAMPLER` is not read.

**Spans leave in batches**, every few seconds. What is left leaves when the host stops, after every hosted
service stopped, so the last requests and jobs are not lost. It is not a hosted service itself for that reason:
hosted services stop in the reverse order they were registered.

**Metrics are kept in memory** — sums and histograms — and leave every `metricExportInterval`, so the interval
only decides how fresh the backend is. What is left leaves when the host stops, with the spans. A `MetricExporter`
registered before replaces the OTLP one, as a `SpanExporter` does. Trantor itself only measures its calls to the
models for now (see [trantor-ai](trantor-ai.md)); the HTTP and queue metrics are still to come.

No logs over OTLP: logs carry the ids of the trace instead (see below).

---

## What Trantor traces

A request that calls another service and dispatches a job, and the job, are one trace:

```
POST /signups                         SERVER    trantor-web
├── GET                               CLIENT    trantor-web-client
│   └── GET /quotes/{destination}     SERVER    (the other service)
└── send signups                      PRODUCER  trantor-core
    └── process signups               CONSUMER  trantor-core
        └── GET                       CLIENT    trantor-web-client
```

All of them follow the OpenTelemetry semantic conventions: HTTP (Stable) and messaging (Development).

The calls to the models, their tools and the runs of the agents have spans of their own inside, following the
conventions for generative AI; see [the telemetry of trantor-ai](trantor-ai.md#telemetry).

### The server

Every request is a `SERVER` span named `{method} {route}` — `GET /orders/{id}`, never the path, which would give
one name per order. A request that matches no route is named after its method alone.

- It goes on from the `traceparent` header of the caller, and it is current while the handlers run, so what
  they do hangs from it.
- A 5xx fails it. `error.type` is the exception an error handler took, or the status when none did. A 4xx does
  not: it is the client's mistake.
- `url.query` hides the values of signatures (`sig`, `X-Amz-Signature`...), as the conventions ask.
- A request answered with `ctx.future` ends its span when the future does.

### The HTTP client

`addHttpClient()` gives an `HttpClient` that traces every call when there is an `OpenTelemetry` in the container
(see [trantor-web-client](trantor-web-client.md)). A client built by hand traces with `traced`:

```kotlin
val client = OkHttpHttpClient(config).traced(openTelemetry)
```

- Every call is a `CLIENT` span named after its method, and carries `traceparent` to the server.
- A 4xx or a 5xx fails it, and so does an exception, typed by its cause (`java.net.SocketTimeoutException`).
- `url.full` hides the credentials and the signatures of the URL.
- A stream ends its span when the headers arrive, as .NET and the OpenTelemetry instrumentations do: the span
  measures the wait for an answer, not the reading of the body.

### Jobs

Dispatching a job is a `send {queue}` span, child of whoever dispatched it — also when the job leaves on commit.
Its context travels in the message (`Message.traceContext`), next to the correlation id.

Running it is a `process {queue}` span, current while the handler runs. It is a **child** of the send span, and
also links to it. The conventions prefer the link alone, and allow the parent for a single message processed
outside any other span, which is what a worker does: that way a request and the job it dispatched are one tree.
The price: a job retried hours later lands in the same trace.

A handler that throws fails the span. The job type goes in `trantor.message.type`, since the conventions have no
attribute for it, and `messaging.system` is what the queue says it is (`aws_sqs` for SQS).

---

## Logs

Every log line inside a span carries `trace_id` and `span_id`, so a line leads to its trace and a trace to its
lines. The layout decides whether to print them:

```properties
appender.stdout.layout.pattern = %d %-5level [%t] %c{1.} trace=%X{trace_id} span=%X{span_id} - %msg%n
```

`TraceContextDataProvider`, in `trantor-primitives`, adds them. Log4j finds it through `META-INF/services`, so
**a fat jar has to merge its service files** (with Shadow, `mergeServiceFiles()`) or the ids are lost.

---

## Across threads

The current span lives in a thread local, like the MDC, so work handed to another thread loses both.
`TaskPool` carries them. Anything else that hands work over does it with `ContextPropagation`:

```kotlin
val context = ContextPropagation.capture()
executor.submit { ContextPropagation.runWithContext(context) { work() } }
```

---

## Spans of your own

Take the `OpenTelemetry` from the container:

```kotlin
class Checkout(openTelemetry: OpenTelemetry) {
    private val tracer = openTelemetry.getTracer("checkout")

    fun pay(order: Order) {
        val span = tracer.spanBuilder("pay order").startSpan()
        try {
            span.makeCurrent().use { gateway.charge(order) }
        } finally {
            span.end()
        }
    }
}
```

Trantor itself never reads `GlobalOpenTelemetry`: reading it before it is set fixes a no-op for good.

---

## With the Java agent

The OpenTelemetry Java agent brings its own SDK. Do not call `addOpenTelemetry()` next to it, or every span is
sent twice; register the agent's instead, and Trantor traces with it:

```kotlin
services.addSingleton<OpenTelemetry>(GlobalOpenTelemetry.get())
```

The agent also instruments Jetty and OkHttp on its own, so the server and client spans may come twice. Turn its
instrumentation of them off, or leave Trantor's `OpenTelemetry` unregistered and let the agent trace HTTP alone.

---

## Tests

A `SpanExporter` registered before `addOpenTelemetry()` replaces the OTLP one:

```kotlin
services.addSingleton<SpanExporter>(InMemorySpanExporter.create())
services.addOpenTelemetry()
```

Spans still go out in batches. Force them out before looking, and not by stopping the host: that shuts the
exporter down, and `InMemorySpanExporter` forgets what it had on shutdown.

```kotlin
(services.get<OpenTelemetry>() as OpenTelemetrySdk).sdkTracerProvider.forceFlush().join(5, TimeUnit.SECONDS)
```

For the no-op, set `enabled` to `false`.
