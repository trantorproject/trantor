dependencies {
    api(project(":core:trantor-core"))
    implementation("org.eclipse.jetty:jetty-client")
}

extra.set("POM_NAME", "Trantor Http Client")
extra.set("POM_DESCRIPTION", "HttpClient abstraction with implementation based on Jetty")
