package dev.botta.trantor.appServices.auth

import dev.botta.trantor.appServices.AppError

class UnauthorizedAccessError(message: String = "Unauthorized access"): AppError(message)
