plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "com.corebound"
version = providers.gradleProperty("pluginVersion").getOrElse("0.1.0")

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.helpch.at/releases/")
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("me.clip:placeholderapi:2.12.3")
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
            modrinth("placeholderapi", "pIvQcXW8")
        }
    }

    jar {
        archiveBaseName.set("Corebound")
    }
}
