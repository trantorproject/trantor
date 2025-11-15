package dev.botta.trantor.serviceProvider

import kotlin.reflect.*

interface ServiceValueResolver {
    val annotationType: KClass<*>
    fun resolve(annotation: Annotation, paramType: KType, isOptional: Boolean, services: ServiceProvider): ResolvedValue
}
