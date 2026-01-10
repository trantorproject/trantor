import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm") version "2.3.0"
    id("dev.botta.kotlin-conventions") version "0.4.2"
    id("com.vanniktech.maven.publish") version "0.32.0"
}

allprojects {
    group = "dev.botta.trantor"
    version = rootProject.file("VERSION").readText().trim()

    if (project.name == "trantor-bom") return@allprojects

    apply(plugin = "dev.botta.kotlin-conventions")

    repositories { mavenCentral() }

    dependencies {
        api(platform(project(":libraries:trantor-bom")))
        api(kotlin("stdlib"))
        api(kotlin("reflect"))
        if (project.name != "trantor-test") testImplementation(project(":libraries:trantor-test"))
        testImplementation("org.junit.platform:junit-platform-launcher")
    }

    kotlin {
        jvmToolchain(25)
    }

    tasks.named<Copy>("processResources") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}

subprojects {
    // skip folders
    if (project.childProjects.isNotEmpty()) return@subprojects

    apply(plugin = "com.vanniktech.maven.publish")

    mavenPublishing {
        publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
        signAllPublications()
        coordinates(project.group.toString(), project.name, project.version.toString())

        pom {
            name.set(project.findProperty("POM_NAME") as? String ?: project.name)
            description.set(project.findProperty("POM_DESCRIPTION") as? String ?: "Part of Trantor framework")
//            name.set("Kotlin Conventions Gradle Plugin")
//            description.set("A plugin that applies Kotlin conventions")
            inceptionYear.set("2025")
            url.set("https://github.com/trantorproject/trantor")

            licenses {
                license {
                    name.set("MIT License")
                    url.set("http://www.opensource.org/licenses/mit-license.php")
                    distribution.set("http://www.opensource.org/licenses/mit-license.php")
                }
            }

            developers {
                developer {
                    id.set("nbottarini")
                    name.set("Nicolas Bottarini")
                    url.set("https://github.com/nbottarini/")
                    email.set("nicolasbottarini@gmail.com")
                }
            }

            scm {
                connection.set("scm:git:git://github.com/trantorproject/trantor.git")
                developerConnection.set("scm:git:ssh://github.com/trantorproject/trantor.git")
                url.set("https://github.com/trantorproject/trantor")
            }
        }
    }

    tasks.withType<Jar>().configureEach {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

tasks.register("publishAllToMavenCentral") {
    group = "publishing"
    description = "Publish all modules to Maven Central"

    dependsOn(subprojects.mapNotNull {
        it.tasks.findByName("publishAllPublicationsToMavenCentralRepository")
    })
}
