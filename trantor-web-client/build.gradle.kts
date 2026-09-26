dependencies {
    api(project(":trantor-primitives"))
    api(project(":trantor-di"))
    implementation("org.eclipse.jetty:jetty-client")
    implementation("com.squareup.okhttp3:okhttp")
}

extra.set("POM_NAME", "Trantor Http Client")
extra.set("POM_DESCRIPTION", "HttpClient abstraction with implementation based on Jetty")

dependencies {
    testImplementation("com.squareup.okhttp3:mockwebserver3")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    testImplementation(project(":trantor-gson"))
}
