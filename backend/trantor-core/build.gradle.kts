dependencies {
    api(project(":trantor-primitives"))
    api(project(":trantor-hosting"))
    api(project(":trantor-gson"))
    api("dev.botta:cqbus")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("com.github.kagkarlsson:db-scheduler")
}

extra.set("POM_NAME", "Trantor Core")
extra.set("POM_DESCRIPTION", "Trantor framework core")
