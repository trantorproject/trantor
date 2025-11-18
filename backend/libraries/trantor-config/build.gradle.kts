dependencies {
    api(project(":libraries:trantor-core"))
    testImplementation(project(":libraries:trantor-gson"))
}

extra.set("POM_NAME", "Trantor Config")
extra.set("POM_DESCRIPTION", "Powerful and extensible config system")
