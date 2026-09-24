package dev.botta.trantor.primitives.validation

import jakarta.validation.*
import kotlin.reflect.KClass

/**
 * The value may be null, but when it is there it must hold something other than whitespace.
 *
 * `@NotBlank` refuses null, so it cannot guard a field of a partial update, where null means "leave it as it is".
 */
@MustBeDocumented
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [NullOrNotBlankValidator::class])
annotation class NullOrNotBlank(
    val message: String = "{jakarta.validation.constraints.NotBlank.message}",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class NullOrNotBlankValidator: ConstraintValidator<NullOrNotBlank, CharSequence?> {
    override fun isValid(value: CharSequence?, context: ConstraintValidatorContext?) = value == null || value.isNotBlank()
}
