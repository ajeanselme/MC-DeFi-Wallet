plugins {
    kotlin("jvm") version "2.3.20-RC"
    id("com.gradleup.shadow") version "9.3.1"
}

group = "com.eik0"
version = "1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    implementation("com.github.shynixn.mccoroutine:mccoroutine-bukkit-api:2.22.0")
    implementation("com.github.shynixn.mccoroutine:mccoroutine-bukkit-core:2.22.0")
}

val targetJavaVersion = 21
kotlin {
    jvmToolchain(targetJavaVersion)
}

tasks.build {
    dependsOn("shadowJar")
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}


tasks.register("buildWithCopy") {
    dependsOn("shadowJar")

    doLast {
        val uploadPath = file("./upload")
        uploadPath.deleteRecursively()
        uploadPath.mkdirs()

        copy {
            from(project.layout.buildDirectory.dir("libs"))
            include("defi-all.jar")
            into(uploadPath)

            rename("-all", "-${System.currentTimeMillis()}")
        }
    }
}
