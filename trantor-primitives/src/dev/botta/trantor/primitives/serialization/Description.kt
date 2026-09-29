package dev.botta.trantor.primitives.serialization

/**
 * What a class or a field is, for whoever reads its schema: a model filling in the arguments of a tool, or the reader
 * of an API. It says what the name and the type do not, like the unit of an amount or where a value comes from.
 *
 * ```kotlin
 * data class CreateChatbot(
 *     @Description("The name the customers see")
 *     val name: String,
 * )
 * ```
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Description(val value: String)
