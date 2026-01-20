dependencies {
    api(project(":libraries:trantor-core"))
    api(project(":libraries:trantor-tx"))
    implementation("dev.botta:cqbus")
}

extra.set("POM_NAME", "Trantor Events")
extra.set("POM_DESCRIPTION", "Application Events and default implementations")
