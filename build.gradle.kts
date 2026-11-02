plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "com.corebound"
version = "0.1.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.6")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("io.papermc.paper:paper-api:26.2.build.+")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    processResources {
        val pluginVersion = project.version
        inputs.property("version", pluginVersion)
        filesMatching("plugin.yml") {
            expand("version" to pluginVersion)
        }
    }

    test {
        useJUnitPlatform()
    }

    runServer {
        minecraftVersion("26.2")
        downloadPlugins {
            modrinth("fastasyncworldedit", "JOyEqKdC")
        }
    }

    jar {
        archiveBaseName.set("TheCore")
    }
}
