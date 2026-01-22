package dev.botta.trantor.core.auth

import dev.botta.trantor.core.app.AppError

class UnauthorizedAccessError(message: String = "Unauthorized access"): AppError(message)
