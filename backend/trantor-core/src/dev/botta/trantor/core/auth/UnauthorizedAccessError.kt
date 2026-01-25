package dev.botta.trantor.core.auth

import dev.botta.trantor.core.application.ApplicationError

class UnauthorizedAccessError(message: String = "Unauthorized access"): ApplicationError(message)
