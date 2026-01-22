dependencies {
    api(project(":trantor-app"))
    api(project(":trantor-core"))
    api(project(":trantor-gson"))
    api("io.javalin:javalin")
    implementation("org.eclipse.jetty:jetty-client")
    implementation("org.fusesource.jansi:jansi")
}

extra.set("POM_NAME", "Trantor Web")
extra.set("POM_DESCRIPTION", "HttpServer implementation based on Javalin and HttpClient based on Jetty")
