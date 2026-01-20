dependencies {
    api("dev.botta:cqbus")
    api(project(":libraries:trantor-core"))
    api(project(":libraries:trantor-config"))
    api(project(":libraries:trantor-domain"))
    api(project(":libraries:trantor-events"))
    api(project(":libraries:trantor-service-provider"))
    api(project(":libraries:trantor-tx"))
    api(project(":libraries:trantor-hosting"))
    api(project(":libraries:trantor-gson"))
}

extra.set("POM_NAME", "Trantor App Services")
extra.set("POM_DESCRIPTION", "")
