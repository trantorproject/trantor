package dev.botta.trantor.appServices.auth

import dev.botta.trantor.appServices.AppServiceError

class UnauthorizedAccessError(message: String = "Unauthorized access"): AppServiceError(message)
