dependencies {
    api(project(":libraries:trantor-core"))
    api(project(":libraries:trantor-service-provider"))
    testImplementation(project(":libraries:trantor-gson"))
}

extra.set("POM_NAME", "Trantor Config")
extra.set("POM_DESCRIPTION", "Powerful and extensible config system")
