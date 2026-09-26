# trantor-web-client

The HTTP client of Trantor: `HttpClient`, an abstraction with one implementation on **OkHttp**, streaming and
Server-Sent Events included.

---

## The client of the application

```kotlin
services.addHttpClient()
```

`addHttpClient()` registers one `HttpClient` for the whole application, so everybody shares its connections.
It reads `HttpClientSettings` from the `httpClient` section:

```json
{ "httpClient": { "connectTimeout": 5000, "requestTimeout": 30000 } }
```

| Setting | Default | What it is |
|---|---|---|
| `connectTimeout` | `10000` | The longest a connection can take to open, in milliseconds. 0 waits forever |
| `idleTimeout` | `10000` | The longest a connection can go without a byte in either direction |
| `requestTimeout` | `60000` | The longest a call can take, from sending to reading the whole response. 0 has no limit |
| `keepAliveTimeout` | `300000` | How long a connection nobody uses stays open, to be used again |
| `maxConnectionsPerDestination` | `1200` | |
| `followRedirects` | `false` | |

In code, `addHttpClient { settings, _ -> settings.requestTimeout = 0 }`. An `HttpClient` registered before is
kept.

When there is an `OpenTelemetry` in the container, every call is traced (see
[trantor-opentelemetry](trantor-opentelemetry.md)). Without one, the client is OkHttp as it is.

### A client with a name

A key gives another client, with its own section and its own connections, for the calls that need a different
one — another proxy, other certificates:

```kotlin
services.addHttpClient("payments") { settings, _ -> settings.connectTimeout = 2000 }
```

```kotlin
class PaymentGateway(@ServiceKey("payments") private val http: HttpClient)
```

It reads `httpClient.payments`, and does not inherit what `httpClient` says.

A long wait is usually a property of one call, not of a client: a stream takes its own timeouts (below), so a
slow endpoint rarely needs a client of its own.

---

## Calls

```kotlin
val response = http.get("https://api.example.com/orders/7", mapOf("Accept" to "application/json"))
val created = http.post("https://api.example.com/orders", """{"sku":"A-1"}""")
```

`HttpResponse` has `status`, `body`, `bodyBytes`, `contentType` and `headers`. A status of 4xx or 5xx is a
response, not an exception; a call that gets no answer throws `HttpClientError`, with the cause inside.

A body is a `String`, sent as `application/json` unless the request says another `Content-Type`, or a
`MultipartBody` for forms with files.

---

## Streams

`stream` gives the response as soon as its headers arrive, and the body as it is received. It has to be closed.

```kotlin
http.stream(HttpMethods.Post, HttpRequest(url, body), StreamOptions(readTimeout = 120_000)).use { response ->
    SseReader(response.lines()).events().forEach { event -> println(event.data) }
}
```

A stream is not bounded by `requestTimeout`: it ends when the server goes silent for longer than
`StreamOptions.readTimeout` (the `idleTimeout` of the client if not given), or when `totalTimeout` runs out.
`cancel()` stops it from another thread.
