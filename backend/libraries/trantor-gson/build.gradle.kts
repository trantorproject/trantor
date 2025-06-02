dependencies {
    api(project(":libraries:trantor-core"))
    api(project(":libraries:trantor-domain"))
    api("com.google.code.gson:gson")
    implementation(project(":libraries:trantor-service-provider"))
}

extra.set("POM_NAME", "Trantor Gson")
extra.set("POM_DESCRIPTION", "Trantor Gson-based serializer")
