dependencies {
    api(project(":libraries:trantor-tx"))
    api(project(":libraries:trantor-core"))
    api("org.jooq:jooq")
    implementation("com.zaxxer:HikariCP")
    implementation(project(":libraries:trantor-service-provider"))
    implementation(project(":libraries:trantor-config"))
}

extra.set("POM_NAME", "Trantor Data")
extra.set("POM_DESCRIPTION", "JDBC and Jooq based data access layer")
