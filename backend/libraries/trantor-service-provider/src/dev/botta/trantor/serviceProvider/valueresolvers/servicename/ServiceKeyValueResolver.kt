package dev.botta.trantor.serviceProvider.valueresolvers.servicename

import dev.botta.trantor.serviceProvider.*
import dev.botta.trantor.serviceProvider.valueresolvers.ServiceValueResolver
import kotlin.reflect.KType
import kotlin.reflect.jvm.jvmErasure

class ServiceKeyValueResolver: ServiceValueResolver {
    override val annotationType = ServiceKey::class

    override fun resolve(annotation: Annotation, paramType: KType, isOptional: Boolean, services: ServiceProvider): ResolvedValue {
        val key = (annotation as ServiceKey).key
        val type = paramType.jvmErasure.java
        val dependency = services.tryGet(type, key)
        return when {
            dependency != null -> ResolvedValue.Value(dependency)
            isOptional -> ResolvedValue.Skip // use default value
            else -> throw ServiceNotRegisteredError("Missing dependency for type ${type.name} with key $key")
        }
    }
}
