package dev.botta.trantor.di.valueresolvers

import dev.botta.trantor.di.ResolvedValue
import dev.botta.trantor.di.ServiceProvider
import kotlin.reflect.KClass
import kotlin.reflect.KType

interface ServiceValueResolver {
    val annotationType: KClass<*>
    fun resolve(annotation: Annotation, paramType: KType, isOptional: Boolean, services: ServiceProvider): ResolvedValue
}
