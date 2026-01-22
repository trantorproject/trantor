dependencies {
    api(project(":trantor-primitives"))
    testImplementation(project(":trantor-gson"))
}

extra.set("POM_NAME", "Trantor Config")
extra.set("POM_DESCRIPTION", "Powerful and extensible config system")
