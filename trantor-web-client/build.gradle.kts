dependencies {
    api(project(":trantor-primitives"))
    implementation("org.eclipse.jetty:jetty-client")
    implementation("com.squareup.okhttp3:okhttp")
}

extra.set("POM_NAME", "Trantor Http Client")
extra.set("POM_DESCRIPTION", "HttpClient abstraction with implementation based on Jetty")

dependencies {
    testImplementation("com.squareup.okhttp3:mockwebserver3")
}
