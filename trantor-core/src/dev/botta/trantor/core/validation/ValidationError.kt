package dev.botta.trantor.core.validation

class ValidationError(val errors: List<FieldError>):
    RuntimeException("Validation failed: ${errors.joinToString { "${it.field}: ${it.message}" }}") {
}

data class FieldError(
    val field: String,
    val message: String,
)
