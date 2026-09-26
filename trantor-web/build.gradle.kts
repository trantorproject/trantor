dependencies {
    api(project(":trantor-core"))
    api(project(":trantor-gson"))
    api("io.javalin:javalin")
    implementation("org.fusesource.jansi:jansi")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
}

extra.set("POM_NAME", "Trantor Web")
extra.set("POM_DESCRIPTION", "HttpServer implementation based on Javalin")
