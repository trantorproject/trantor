dependencies {
    api(project(":trantor-primitives"))
    api(project(":trantor-domain"))
    api("com.google.code.gson:gson")
    implementation(project(":trantor-di"))
}

extra.set("POM_NAME", "Trantor Gson")
extra.set("POM_DESCRIPTION", "Trantor Gson-based serializer")
