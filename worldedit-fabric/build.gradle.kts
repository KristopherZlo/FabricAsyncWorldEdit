import buildlogic.getLibrary
import buildlogic.stringyLibs
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RemapJarTask
import net.fabricmc.loom.task.RunGameTask

plugins {
    id("fabric-loom")
    `java-library`
    id("buildlogic.platform")
}

platform {
    kind = buildlogic.WorldEditKind.Mod
    includeClasspath = true
}

val fabricApiConfiguration: Configuration = configurations.create("fabricApi")

loom {
    accessWidenerPath.set(project.file("src/upstream/resources/worldedit.accesswidener"))
}

tasks.withType<RunGameTask>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
}

tasks.named<RunGameTask>("runClient") {
    args("--width", "854", "--height", "480")
}


sourceSets {
    named("main") {
        java.setSrcDirs(listOf("src/upstream/java"))
        resources.setSrcDirs(listOf("src/upstream/resources"))
    }
    create("testmod") {
        java.setSrcDirs(listOf("src/testmod/java"))
        resources.setSrcDirs(listOf("src/testmod/resources"))
        compileClasspath += named("main").get().compileClasspath
        runtimeClasspath += named("main").get().runtimeClasspath
    }
}

loom {
    mods {
        create("worldedit") {
            sourceSet(sourceSets["main"])
        }
        create("worldedit-sp-testharness") {
            sourceSet(sourceSets["testmod"])
        }
    }
    runs {
        create("testmodClient") {
            client()
            source(sourceSets["testmod"])
            programArgs("--width", "854", "--height", "480")
            if (project.hasProperty("quickPlayWorld")) {
                programArgs("--quickPlaySingleplayer", project.property("quickPlayWorld") as String)
            }
        }
    }
}

repositories {
    verifyEngineHubRepositories()
}

dependencies {
    "api"(project(":worldedit-core"))

    "minecraft"(libs.fabric.minecraft)
    "mappings"(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-${libs.versions.parchment.minecraft.get()}:${libs.versions.parchment.mappings.get()}@zip")
    })
    "modImplementation"(libs.fabric.loader)
    "include"(libs.cuiProtocol.fabric)
    "modImplementation"(libs.cuiProtocol.fabric)
    "include"(libs.parallelgzip)
    "modImplementation"(libs.parallelgzip)
    "include"(libs.sparsebitset)
    "modImplementation"(libs.sparsebitset)
    // FAWE core runtime deps not provided by vanilla/Fabric (Spigot ships some of
    // these on the Bukkit side). ZSTD must not be relocated:
    // https://github.com/luben/zstd-jni/issues/189
    "include"(libs.zstd)
    "implementation"(libs.zstd)
    // lz4 is NOT bundled here: vanilla 1.21.x ships org.lz4:lz4-java itself and
    // the at.yawk.lz4 fork conflicts with it on the org.lz4 capability.
    "include"(libs.json.simple)
    "implementation"(libs.json.simple)
    "include"(libs.jchronic)
    "implementation"(libs.jchronic)
    // Vanilla shipped snakeyaml up to 1.21.x but dropped it; FAWE's settings
    // parser (config.yml) needs it at runtime.
    "include"(libs.snakeyaml)
    "implementation"(libs.snakeyaml)

    // [1] Load the API dependencies from the fabric mod json...
    @Suppress("UNCHECKED_CAST")
    val fabricModJson = file("src/upstream/resources/fabric.mod.json").bufferedReader().use {
        groovy.json.JsonSlurper().parse(it) as Map<String, Map<String, *>>
    }
    val wantedDependencies = (fabricModJson["depends"] ?: error("no depends in fabric.mod.json")).keys
        .filter { it == "fabric-api-base" || it.contains(Regex("v\\d$")) }
        .toSet()
    // [2] Request the matching dependency from fabric-loom
    for (wantedDependency in wantedDependencies) {
        val dep = fabricApi.module(wantedDependency, libs.versions.fabric.api.get())
        "include"(dep)
        "modImplementation"(dep)
    }

    // No need for this at runtime
    "modCompileOnly"(libs.fabric.permissions.api)

    // Silence some warnings, since apparently this isn't on the compile classpath like it should be.
    "compileOnly"(libs.errorprone.annotations)
}

configure<BasePluginExtension> {
    archivesName.set("${project.name}-mc${libs.fabric.minecraft.get().version}")
}

plugins.withId("maven-publish") {
    configure<PublishingExtension> {
        publications.named<MavenPublication>("maven") {
            artifactId = the<BasePluginExtension>().archivesName.get()
            from(components["java"])
        }
    }
}

tasks.named<Copy>("processResources") {
    val internalVersion = project.ext["internalVersion"]
    // this will ensure that this task is redone when the versions change.
    inputs.property("version", internalVersion)
    filesMatching("fabric.mod.json") {
        this.expand(mapOf("version" to internalVersion))
    }
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("dist-dev")
    dependencies {
        relocate("org.antlr.v4", "com.sk89q.worldedit.antlr4")
        relocate("net.royawesome.jlibnoise", "com.sk89q.worldedit.jlibnoise")

        include(dependency("org.antlr:antlr4-runtime"))
        include(dependency("com.sk89q.lib:jlibnoise"))
    }
}

tasks.register<RemapJarTask>("remapShadowJar") {
    val shadowJar = tasks.getByName<ShadowJar>("shadowJar")
    dependsOn(shadowJar)
    inputFile.set(shadowJar.archiveFile)
    archiveFileName.set(shadowJar.archiveFileName.get().replace(Regex("-dev\\.jar$"), ".jar"))
    addNestedDependencies.set(true)
}

tasks.named("assemble").configure {
    dependsOn("remapShadowJar")
}
