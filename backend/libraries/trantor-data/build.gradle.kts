dependencies {
    api(project(":libraries:trantor-tx"))
    api(project(":libraries:trantor-core"))
    api("org.jooq:jooq")
    implementation("com.zaxxer:HikariCP")
    implementation(project(":libraries:trantor-service-provider"))
    implementation(project(":libraries:trantor-config"))
}
