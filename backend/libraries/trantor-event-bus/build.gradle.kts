dependencies {
    api(project(":libraries:trantor-core"))
    implementation("dev.botta:cqbus")
}

extra.set("POM_NAME", "Trantor Event Bus")
extra.set("POM_DESCRIPTION", "Event Bus abstraction and multiple implementations")
