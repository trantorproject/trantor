dependencies {
    api(project(":libraries:trantor-core"))
    api("io.javalin:javalin")
    implementation("org.eclipse.jetty:jetty-client")
}

extra.set("POM_NAME", "Trantor Web")
extra.set("POM_DESCRIPTION", "HttpServer implementation based on Javalin and HttpClient based on Jetty")
