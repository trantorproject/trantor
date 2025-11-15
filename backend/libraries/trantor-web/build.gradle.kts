dependencies {
    api(project(":libraries:trantor-core"))
    implementation("org.eclipse.jetty:jetty-client")
    api("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-body-limit")
    implementation("io.ktor:ktor-server-websockets")
}

extra.set("POM_NAME", "Trantor Web")
extra.set("POM_DESCRIPTION", "HttpServer implementation based on Ktor and HttpClient based on Jetty")
