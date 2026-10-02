# Changelog

All notable changes to Trantor. Versions before 0.9.0 are in the git history.

## [0.9.0] - 2026-10-02

### Breaking

- **trantor-web:** the HTTP server listens on port `8080` by default, where it listened on `80`. Set
  `httpServer.port` to keep `80`.
- **trantor-core:** `QueueFactory.createFromConfig(name, config: Config)` is now
  `createFromConfig(name, section: ConfigSection)`: a queue driver gets the section the queue is declared in
  (`jobs.queues.<key>`) and reads its settings from there.
- **trantor-queues-sqs:** an SQS queue reads its settings from its own declaration, `jobs.queues.<key>`, where it
  read them from `queues.<name>`. Move `region`, `endpointOverride`, `pollMaxMessages`, `pollWaitTimeSeconds` and
  `pollVisibilityTimeout` into the section of the queue; `aws.region` and `aws.endpointOverride` still apply to a
  queue that names none.

### Added

- **trantor-core, trantor-web:** `app.addHandler<PlaceOrderHandler>()` registers a handler by its class, for the
  request its declaration names. The container builds one for every request, with the scoped services of that
  request. A class that is not a handler, or whose request is a type parameter, fails when it is registered.
  `app.addHandler(handler)` registers an instance, context aware or not.
- **trantor-hosting, trantor-config:** the arguments of `builder(args)` are read as configuration, as .NET reads
  them: `--key=value`, `key=value`, `--key value` and `--flag`. They override the settings files and the
  environment variables, and `--env staging` picks the settings file. `ConfigManager.addCommandLine(args)` adds
  them to any configuration.
- **trantor-core:** a `memory` queue driver, which `JobsModule` registers, for development, tests and applications
  that run as a single instance. A poll waits for a message, a polled message is hidden for
  `pollVisibilityTimeout` and comes back as a retry, and `delaySeconds` holds a message back. Its settings have the
  names of those of `sqs`, so a queue can change driver and keep them.
