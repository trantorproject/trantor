dependencies {
    api(project(":core:trantor-core"))
    api(project(":core:trantor-config"))
    testImplementation(project(":libraries:trantor-gson"))
}

extra.set("POM_NAME", "Trantor Service Provider")
extra.set("POM_DESCRIPTION", "Simple and powerful dependency injection")
