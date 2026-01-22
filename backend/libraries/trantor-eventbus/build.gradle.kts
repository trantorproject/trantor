dependencies {
    api(project(":core:trantor-core"))
    implementation("dev.botta:cqbus")
}

extra.set("POM_NAME", "Trantor Events")
extra.set("POM_DESCRIPTION", "Application Events and default implementations")
