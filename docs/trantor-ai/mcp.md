# MCP: the tools of other servers

An MCP server offers tools that any application can use: GitHub's issues, a database, the files of a folder.
trantor-ai has a client of its own, over HTTP and over stdio, and the tools of a server go into a generation or an
agent like any other tool. It speaks the 2026-07-28 revision of the protocol, where every request stands on its
own, and the revisions before it, which most servers still speak.

This section is about using the servers of others. To offer the use cases of the application as an MCP server, see
[trantor-mcp-server](../trantor-mcp-server.md).

## Declaring the servers

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
[Interpolation](../trantor-config.md#interpolation)). A header or a variable of a process has a name the environment
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

## Their tools in a run

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
  application to decide. A call that needs approval pauses the run, as any [other](tools.md#approvals) does.
- **The schema is the server's, sent without strict mode**, which would ask the provider to rewrite a schema the
  application did not write.
- **The server is asked every time `tools(...)` is called.** Called once when the agent is built, the agent keeps
  those tools; called on every run, it sees the ones the server adds.

What the tool answers reaches the model as text, a line per piece. With no text but a JSON answer
(`structuredContent`), the model gets the JSON. A piece the model cannot get yet, like an image, becomes a line that
says there was one, so the answer does not look cut. A tool that ran and failed reaches the model as a `ToolError`
with what the server said, so it can fix the call; a call the server turned down is an `McpError`, which the model
reads as [any failure](tools.md#when-a-tool-fails).

## Calling a tool yourself

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

## Old and new servers

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

## Servers over stdio

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

## Timeouts and cancellation

The `callOptions` of the run reach every call of its MCP tools, as they reach the calls to the model:

- **A request waits for the shorter of the timeout of the run and the `requestTimeoutSeconds` of the server** (two
  minutes by default), so a server that hangs does not hold a long run. One that does not get its answer in time
  fails with an `McpError` that says so.
- **Cancelling the run stops the request** with a `CancelledError`, even while it waits for the answer, and the
  server is told, so it can stop what it does: over stdio and on a server of before, with `notifications/cancelled`;
  on a server of 2026-07-28 over HTTP, closing the connection is how it is told.

A server that cannot be reached is an `McpError` that names it.

