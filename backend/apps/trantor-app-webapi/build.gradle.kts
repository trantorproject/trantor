dependencies {
    api(project(":libraries:trantor-app-services"))
    api(project(":libraries:trantor-config"))
    api(project(":libraries:trantor-core"))
    api(project(":libraries:trantor-gson"))
    api(project(":libraries:trantor-service-provider"))
    api(project(":libraries:trantor-web"))
}

extra.set("POM_NAME", "Trantor Web Api")
extra.set("POM_DESCRIPTION", "Trantor starter for building web apis")
