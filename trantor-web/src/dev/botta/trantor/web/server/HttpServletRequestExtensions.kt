package dev.botta.trantor.web.server

import jakarta.servlet.http.HttpServletRequest

/**
 * The IP address of who sent the request, as `client.address` of OpenTelemetry has it. Jetty writes an IPv6 one
 * between brackets (`[0:0:0:0:0:0:0:1]`), as it goes in a URL to set it apart from the port; the brackets are not
 * part of the address, and with them the same client would count as two.
 */
val HttpServletRequest.clientAddress: String get() = remoteAddr.removeSurrounding("[", "]")
