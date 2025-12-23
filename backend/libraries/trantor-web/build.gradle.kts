dependencies {
    api(project(":libraries:trantor-core"))
    api("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-call-id")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-body-limit")
    implementation("io.ktor:ktor-server-double-receive")
    implementation("io.ktor:ktor-server-websockets")
    implementation("io.ktor:ktor-client-apache5")
    implementation("org.fusesource.jansi:jansi")
}

extra.set("POM_NAME", "Trantor Web")
extra.set("POM_DESCRIPTION", "HttpServer implementation based on Ktor and HttpClient based on Jetty")
