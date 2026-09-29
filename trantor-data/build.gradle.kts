dependencies {
    api(project(":trantor-primitives"))
    api(project(":trantor-core"))
    api("org.jooq:jooq")
    implementation("dev.botta:cqbus")
    implementation("com.zaxxer:HikariCP")
    implementation(project(":trantor-di"))
    implementation(project(":trantor-config"))
    compileOnly("org.postgresql:postgresql:42.7.13")

    testImplementation(project(":trantor-gson"))
    testImplementation("org.postgresql:postgresql:42.7.13")
}

extra.set("POM_NAME", "Trantor Data")
extra.set("POM_DESCRIPTION", "JDBC and Jooq based data access layer")
