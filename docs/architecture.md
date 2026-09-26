# Trantor Architecture

**Trantor** is a lightweight backend framework inspired by **.NET Core**, **Laravel**, and **Spring Boot**.

Its main goals are:
- **Explicitness over magic**
- **Simplicity**
- **Pragmatism**

Trantor avoids heavy conventions or hidden behavior. Most framework behavior is explicit in code, which makes applications easier to understand, debug, and evolve.

The framework follows widely adopted industry practices such as:
- Clean Architecture
- Clean Code
- Simple Design
- Pragmatic Domain-Driven Design

# Main Gradle Packages

Trantor is organized into several Gradle modules. Each module focuses on a specific responsibility.

## Core Modules

### trantor-core

The central module of the framework.

It defines the main application services and abstractions used by most Trantor applications, including:
- Application lifecycle
- Authentication
- Broadcasting
- Caching
- Events
- Jobs
- Queues
- Scheduling
- Transactions

Most other modules integrate with or extend these core abstractions.

---

### trantor-hosting

Defines the **Host** abstraction, heavily inspired by the hosting model of **.NET Core**.

A host is responsible for managing the lifecycle of the application.

Responsibilities include:
- Application startup and shutdown
- Access to configuration
- Dependency injection container
- Environment information
- Module registration
- Hosted services

Hosted services are components that run alongside the application during its lifetime.

---

### trantor-di

Dependency Injection system used by the framework.

Main components:
- **ServiceRegistry** — used to register services
- **ServiceProvider** — used to resolve services

The design is inspired by the **.NET Core DI container** and emphasizes explicit service registration.

---

### trantor-config

Configuration system inspired by the configuration system of **.NET Core**.

Features:
- Multiple configuration providers
- Environment-based configuration
- Centralized configuration access

---

## Data Access

### trantor-data

Provides utilities for database access, including:
- JDBC integration
- jOOQ integration

This module helps standardize data access patterns in Trantor applications.

---

## Web

### trantor-web

Main module used to build **web applications and APIs**.

Key components include:

- `WebApplication` (a specialized Host)
- Embedded web server
- HTTP request handling

The web layer uses:

- **Javalin** for HTTP routing
- **Jetty** as the underlying web server

---

### trantor-web-client

HTTP client abstraction, `HttpClient`, with its implementation on **OkHttp**, streaming and SSE included.

`addHttpClient()` registers one for the whole application, read from the `httpClient` section.

---

## AWS Integration

### trantor-aws

Provides integrations with **Amazon Web Services (AWS)**.

Includes utilities and services commonly used when running Trantor applications on AWS infrastructure.

---

### trantor-queues-sqs

Implementation of the **queue abstractions defined in `trantor-core`** using **Amazon SQS**.

Allows applications to process background jobs using SQS.

---

## Background Processing

### trantor-taskpool

Provides a managed **thread pool** for executing asynchronous tasks.

This component is implemented as a **HostedService**, meaning it is tied to the lifecycle of the application host.

---

## AI

### trantor-ai

Model-level access to LLM providers, with one contract shared by all of them.

Provides:
- `ChatModel`: generate and stream, with messages, tools, structured output and reasoning
- `ModelRegistry`: resolves a model from a `provider/model` reference or from an alias in configuration
- Middlewares that wrap any model, such as retries with backoff
- Provider adapters, currently OpenAI

Provider specifics that do not fit the shared contract travel in `providerOptions` and come back in
`providerMetadata`, so that using one does not mean leaving the contract.

See `docs/trantor-ai.md`.

---

## Domain Utilities

### trantor-domain

Provides useful abstractions and base classes for the **domain layer** of applications.

Includes utilities for:
- Identifiers
- Aggregates
- Domain errors
- Domain events
- Repository abstractions

See `docs/trantor-domain.md`.

---

## Serialization

### trantor-gson

Provides a JSON serializer implementation based on **Gson**.

Used for serialization and deserialization of objects.

---

## Testing

### trantor-test

Testing utilities designed for applications built with Trantor.

Includes preconfigured dependencies and helpers for:
- JUnit
- AssertJ
- Rest-Assured
- MockK

---

## Low-Level Utilities

### trantor-primitives

Contains the lowest-level utilities used across the framework.

Includes:
- Logging utilities
- Kotlin extensions
- Serialization helpers

---

## Dependency Management

### trantor-bom

Bill of Materials (BOM) module used to centralize dependency versions.

This module defines versions for:
- External libraries
- Trantor modules

Applications using Trantor can import this BOM to ensure consistent dependency versions.

---

# Additional Documentation

More detailed documentation about specific Trantor modules can be found in:

- [Conventions](conventions.md) - code style, naming and the patterns every module follows
- [Testing](testing.md) - how tests are laid out, named and run
- [trantor-di](trantor-di.md)
- [trantor-config](trantor-config.md)
- [trantor-hosting](trantor-hosting.md)
- [trantor-core](trantor-core.md)
- [trantor-domain](trantor-domain.md)
- [trantor-web](trantor-web.md)
- [trantor-ai](trantor-ai.md)
