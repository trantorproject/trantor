package dev.botta.trantor.core.auth

import dev.botta.trantor.core.application.ApplicationError

class NotAuthenticatedError: ApplicationError("Not Authenticated")
