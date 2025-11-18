dependencies {
    api(project(":libraries:trantor-core"))
    api(project(":libraries:trantor-config"))
    testImplementation(project(":libraries:trantor-gson"))
}

extra.set("POM_NAME", "Trantor Service Provider")
extra.set("POM_DESCRIPTION", "Simple and powerful dependency injection")
