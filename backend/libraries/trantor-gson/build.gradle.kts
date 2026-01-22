dependencies {
    api(project(":core:trantor-core"))
    api(project(":libraries:trantor-domain"))
    api("com.google.code.gson:gson")
    implementation(project(":core:trantor-di"))
}

extra.set("POM_NAME", "Trantor Gson")
extra.set("POM_DESCRIPTION", "Trantor Gson-based serializer")
