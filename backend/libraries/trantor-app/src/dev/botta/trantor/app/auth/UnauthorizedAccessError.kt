package dev.botta.trantor.app.auth

import dev.botta.trantor.app.AppError

class UnauthorizedAccessError(message: String = "Unauthorized access"): AppError(message)
