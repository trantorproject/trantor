dependencies {
    api(project(":core:trantor-core"))
    api("org.jooq:jooq")
    implementation("dev.botta:cqbus")
    implementation("com.zaxxer:HikariCP")
    implementation(project(":core:trantor-di"))
    implementation(project(":core:trantor-config"))
}

extra.set("POM_NAME", "Trantor Data")
extra.set("POM_DESCRIPTION", "JDBC and Jooq based data access layer")
