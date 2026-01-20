dependencies {
    api(project(":libraries:trantor-core"))
    api(project(":libraries:trantor-app-services"))
    api(project(":libraries:trantor-gson"))
    api(project(":libraries:trantor-hosting"))
    api("io.javalin:javalin")
    implementation("org.eclipse.jetty:jetty-client")
    implementation("org.fusesource.jansi:jansi")
}

extra.set("POM_NAME", "Trantor Web")
extra.set("POM_DESCRIPTION", "HttpServer implementation based on Javalin and HttpClient based on Jetty")
