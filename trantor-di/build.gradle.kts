dependencies {
    api(project(":trantor-primitives"))
    api(project(":trantor-config"))
    testImplementation(project(":trantor-gson"))
}

extra.set("POM_NAME", "Trantor DI")
extra.set("POM_DESCRIPTION", "Simple and powerful dependency injection")
