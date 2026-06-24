plugins {
    kotlin("jvm") version "2.4.0"
    id("com.gradleup.shadow") version "9.4.2"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://repo.triumphteam.dev/snapshots")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // Gson is provided by Paper at runtime; needed at compile time for the /pair request payload.
    compileOnly("com.google.code.gson:gson:2.11.0")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    // Shared code from the server repo (git submodule, wired via includeBuild in settings.gradle.kts).
    // Its transitive runtime deps (mysql, triumph-gui, kotlin-stdlib) are shaded in below.
    implementation("fr.overrride.skyblock:common")
    // Kotlin coroutine HTTP client for the /pair call to the central API (shaded into the jar).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")
}

kotlin {
    jvmToolchain(25)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        relocate("com.mysql", "fr.overrride.minecraft.skyblockplugin.libs.mysql")
        relocate("com.google.protobuf", "fr.overrride.minecraft.skyblockplugin.libs.protobuf")
        relocate("dev.triumphteam", "fr.overrride.minecraft.skyblockplugin.libs.triumphteam")
        // Ktor's CIO engine is registered via a service file; merge them so it's discoverable.
        mergeServiceFiles()
    }

    runServer {
        minecraftVersion("1.21.11")
        jvmArgs("-Xms1G", "-Xmx1G")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }
}
