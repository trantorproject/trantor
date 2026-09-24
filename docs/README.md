# Trantor documentation

Start with [architecture](architecture.md) for what Trantor is and how the modules divide the work.

## Working in this repository

- [Conventions](conventions.md) - code style, naming, settings classes, the registration patterns
- [Testing](testing.md) - layout, naming, fixtures, how to run and how to check a test really ran

The rules an agent needs in one place are in [AGENTS.md](../AGENTS.md) at the root.

## Modules

| Doc | Module | What it covers |
|---|---|---|
| [trantor-di](trantor-di.md) | `trantor-di` | The container: lifetimes, keys, `addConfig`, `configure`, value resolvers |
| [trantor-config](trantor-config.md) | `trantor-config` | Stacked providers, sections, paths |
| [trantor-hosting](trantor-hosting.md) | `trantor-hosting` | `Host`, `HostBuilder`, hosted services, modules |
| [trantor-core](trantor-core.md) | `trantor-core` | Application pipeline, events, jobs, queues, cache, transactions |
| [trantor-domain](trantor-domain.md) | `trantor-domain` | Aggregates, ids, domain events, domain errors, `fail` and `Ensure`, repositories, `Email` and `Money` |
| [trantor-web](trantor-web.md) | `trantor-web` | HTTP server, routes, controllers, error handlers, websockets |
| [trantor-ai](trantor-ai.md) | `trantor-ai` | Chat models, the OpenAI and Anthropic adapters, the model registry and the capability catalog |

Not documented yet: `trantor-data`, `trantor-web-client`, `trantor-gson`,
`trantor-taskpool`, `trantor-primitives`, `trantor-aws`, `trantor-queues-sqs`. Their KDoc and tests are
the reference for now.
