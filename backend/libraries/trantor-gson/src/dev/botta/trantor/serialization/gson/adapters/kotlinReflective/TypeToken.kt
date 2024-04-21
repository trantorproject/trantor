package dev.botta.trantor.serialization.gson.adapters.kotlinReflective

import com.google.gson.internal.`$Gson$Types`
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import kotlin.reflect.*
import kotlin.reflect.jvm.javaType

/**
 * Helper method for converting a [TypeToken] to a corresponding Kotlin class.
 *
 * Note that if the `TypeToken` represents a generic type, the returned [KClass] is for the
 * raw type.
 */
internal fun <T : Any> TypeToken<T>.toKClass(): KClass<T> =
    @Suppress("UNCHECKED_CAST")
    (rawType as Class<T>).kotlin

/**
 * Resolves for the type of the [property] that is member of the [TypeToken].
 */
internal fun TypeToken<*>.resolveType(propertyType: Type): TypeToken<*> =
    TypeToken.get(`$Gson$Types`.resolve(type, rawType, propertyType))
