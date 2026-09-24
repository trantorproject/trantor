dependencies {
    api(project(":trantor-primitives"))
    api(project(":trantor-hosting"))
    api(project(":trantor-gson"))
    api("dev.botta:cqbus")
    // api, because a ScheduledJob declares its Schedule with db-scheduler types
    api("com.github.kagkarlsson:db-scheduler")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("org.hibernate.validator:hibernate-validator")
}

extra.set("POM_NAME", "Trantor Core")
extra.set("POM_DESCRIPTION", "Trantor framework core")
