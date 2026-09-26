dependencies {
    api(project(":trantor-hosting"))
    api("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    testImplementation(project(":trantor-gson"))
}

extra.set("POM_NAME", "Trantor OpenTelemetry")
extra.set("POM_DESCRIPTION", "OpenTelemetry SDK with the OTLP exporter for Trantor framework")
