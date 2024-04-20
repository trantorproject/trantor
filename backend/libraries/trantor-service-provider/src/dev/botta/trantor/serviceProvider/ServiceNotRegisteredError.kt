package dev.botta.trantor.serviceProvider

class ServiceNotRegisteredError(message: String): Exception(message) {
    constructor(type: Class<*>, key: String?): this(
        if (key == null) "Service '${type.name}' not registered" else "Service '${type.name}' not registered with key '${key}'"
    )
}
